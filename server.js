'use strict';
const express = require('express');
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { spawn } = require('child_process');
const crypto = require('crypto');

const app = express();
const PORT = Number(process.env.PORT || 3000);
const MAX_UPLOAD = Number(process.env.MAX_UPLOAD_MB || 100) * 1024 * 1024;
const UPLOAD_DIR = path.join(__dirname, 'uploads');
fs.mkdirSync(UPLOAD_DIR, { recursive: true });
app.disable('x-powered-by');
app.use(express.json({ limit: '32kb' }));
app.use(express.static(path.join(__dirname, 'public')));
const upload = multer({ dest: UPLOAD_DIR, limits: { fileSize: MAX_UPLOAD, files: 1 }, fileFilter(_req, file, cb) {
  const allowed = new Set(['audio/mpeg','audio/mp3','audio/wav','audio/x-wav','audio/wave','audio/flac','audio/aac','audio/mp4','audio/ogg','audio/webm','audio/x-m4a','video/mp4','application/octet-stream']);
  cb(allowed.has(file.mimetype) || /\.(mp3|wav|flac|aac|m4a|ogg|opus|webm|mp4)$/i.test(file.originalname) ? null : new Error('نوع فایل پشتیبانی نمی‌شود.'), true);
}});

function validPublicUrl(raw) {
  let u; try { u = new URL(raw); } catch { throw new Error('آدرس واردشده معتبر نیست.'); }
  if (u.protocol !== 'https:') throw new Error('فقط نشانی HTTPS مجاز است.');
  const h = u.hostname.toLowerCase();
  const allowed = h === 'suno.com' || h.endsWith('.suno.com') || h === 'suno.ai' || h.endsWith('.suno.ai');
  if (!allowed) throw new Error('فقط لینک‌های عمومی Suno پذیرفته می‌شوند.');
  if (u.username || u.password || u.port) throw new Error('نشانی واردشده مجاز نیست.');
  return u;
}
function cleanName(s) { return String(s || 'suno-track').normalize('NFKC').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '').replace(/\s+/g, ' ').trim().slice(0, 100) || 'suno-track'; }
function extractAudio(html, pageUrl) {
  const candidates = [];
  const add = v => { if (typeof v === 'string' && /^https:\/\//i.test(v) && /\.(mp3|wav|m4a|aac|ogg|opus)(?:[?#]|$)/i.test(v)) candidates.push(v.replace(/&amp;/g,'&')); };
  for (const m of html.matchAll(/<meta[^>]+(?:property|name)=["'](?:og:audio|og:audio:secure_url|twitter:player:stream)["'][^>]+content=["']([^"']+)/gi)) add(m[1]);
  for (const m of html.matchAll(/<audio[^>]+src=["']([^"']+)/gi)) add(m[1]);
  for (const m of html.matchAll(/(?:audio_url|audioUrl|stream_url|streamUrl)\s*[:=]\s*["']([^"']+)/gi)) add(m[1]);
  const safe = candidates.find(v => { try { const h = new URL(v).hostname.toLowerCase(); return h === 'suno.com' || h.endsWith('.suno.com') || h === 'suno.ai' || h.endsWith('.suno.ai'); } catch { return false; } });
  const title = (html.match(/<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)/i) || html.match(/<title[^>]*>([^<]+)/i) || [,'Suno track'])[1];
  return { title: title.replace(/&amp;/g,'&').replace(/&#39;/g,"'").slice(0,180), audioUrl: safe || null, source: new URL(pageUrl).origin };
}
async function getPageInfo(raw) {
  const u = validPublicUrl(raw);
  if (!/(^|\.)suno\.(com|ai)$/i.test(u.hostname)) throw new Error('دامنه لینک Suno معتبر نیست.');
  const res = await fetch(u, { redirect: 'follow', headers: { 'User-Agent': 'Mozilla/5.0 (compatible; PersonalSunoDownloader/1.0)', 'Accept': 'text/html' }, signal: AbortSignal.timeout(15000) });
  if (!res.ok) throw new Error('دریافت صفحه Suno انجام نشد.');
  const finalUrl = validPublicUrl(res.url);
  const html = await res.text();
  return { ...extractAudio(html, finalUrl.href), officialUrl: finalUrl.href };
}
app.get('/api/health', (_req,res) => {
  const ff = spawn('ffmpeg', ['-version']); let done = false;
  ff.on('error', () => { if (!done) { done = true; res.json({ ok:true, ffmpeg:false, maxUploadMb: MAX_UPLOAD/1048576 }); } });
  ff.on('close', code => { if (!done) { done = true; res.json({ ok:true, ffmpeg: code === 0, maxUploadMb: MAX_UPLOAD/1048576 }); } });
});
app.get('/api/info', async (req,res) => {
  try { const info = await getPageInfo(String(req.query.url || '')); res.json({ ok:true, title:info.title, audioAvailable:!!info.audioUrl, officialUrl:info.officialUrl, note:info.audioUrl ? 'فایل صوتی عمومی شناسایی شد.' : 'فایل صوتی مستقیم در صفحه پیدا نشد؛ صفحه رسمی را باز کنید.' }); }
  catch(e) { res.status(400).json({ ok:false, error:e.message }); }
});
app.get('/api/download', async (req,res) => {
  let inputPath = null, outputPath = null;
  try {
    const info = await getPageInfo(String(req.query.url || ''));
    if (!info.audioUrl) throw new Error('لینک مستقیم صوتی عمومی در صفحه پیدا نشد.');
    const a = new URL(info.audioUrl); const h = a.hostname.toLowerCase();
    if (!(h === 'suno.com' || h.endsWith('.suno.com') || h === 'suno.ai' || h.endsWith('.suno.ai'))) return res.status(403).json({ok:false,error:'میزبان فایل صوتی مجاز نیست.'});
    const upstream = await fetch(a, { headers:{'User-Agent':'Mozilla/5.0'}, signal:AbortSignal.timeout(30000) });
    if (!upstream.ok || !upstream.body) return res.status(502).json({ok:false,error:'دریافت فایل صوتی از میزبان انجام نشد.'});
    const { Readable } = require('stream'); const { pipeline } = require('stream/promises');
    inputPath = path.join(os.tmpdir(), `suno-in-${crypto.randomUUID()}${path.extname(a.pathname)||'.audio'}`);
    await pipeline(Readable.fromWeb(upstream.body), fs.createWriteStream(inputPath));
    const format = String(req.query.format || 'mp3').toLowerCase();
    if (!['mp3','wav'].includes(format)) return res.status(400).json({ok:false,error:'فرمت خروجی نامعتبر است.'});
    outputPath = path.join(os.tmpdir(), `suno-out-${crypto.randomUUID()}.${format}`);
    const args = format === 'mp3' ? ['-y','-i',inputPath,'-vn','-codec:a','libmp3lame','-q:a','2',outputPath] : ['-y','-i',inputPath,'-vn','-codec:a','pcm_s16le',outputPath];
    await new Promise((resolve,reject)=>{ const ff=spawn('ffmpeg',args); let error=''; ff.stderr.on('data',d=>error+=d.toString()); ff.on('error',reject); ff.on('close',code=>code===0?resolve():reject(new Error(code===127?'FFmpeg روی سرور نصب نیست.':`تبدیل صوت انجام نشد. ${error.slice(-300)}`))); });
    fs.unlink(inputPath,()=>{}); inputPath=null;
    res.download(outputPath, `${cleanName(info.title)}.${format}`, ()=>{ if(outputPath) fs.unlink(outputPath,()=>{}); });
  } catch(e) { if(inputPath) fs.unlink(inputPath,()=>{}); if(outputPath) fs.unlink(outputPath,()=>{}); if (!res.headersSent) res.status(400).json({ok:false,error:e.message}); }
});
app.post('/api/convert', upload.single('audio'), async (req,res) => {
  if (!req.file) return res.status(400).json({ok:false,error:'فایل صوتی انتخاب نشده است.'});
  const format = String(req.body.format || 'mp3').toLowerCase();
  if (!['mp3','wav'].includes(format)) { fs.unlink(req.file.path,()=>{}); return res.status(400).json({ok:false,error:'فرمت خروجی فقط MP3 یا WAV است.'}); }
  const base = cleanName(req.body.title || path.parse(req.file.originalname).name);
  const out = path.join(os.tmpdir(), `suno-${crypto.randomUUID()}.${format}`);
  const args = format === 'mp3' ? ['-y','-i',req.file.path,'-vn','-codec:a','libmp3lame','-q:a','2',out] : ['-y','-i',req.file.path,'-vn','-codec:a','pcm_s16le',out];
  const ff = spawn('ffmpeg', args); let err=''; ff.stderr.on('data',d=>err+=d.toString());
  ff.on('error',()=>{fs.unlink(req.file.path,()=>{});res.status(503).json({ok:false,error:'FFmpeg روی سرور نصب نیست. فایل اصلی را می‌توانید مستقیم نگه دارید.'});});
  ff.on('close',code=>{ fs.unlink(req.file.path,()=>{}); if(code!==0) return res.status(422).json({ok:false,error:'تبدیل فایل انجام نشد.'}); res.download(out, `${base}.${format}`, ()=>fs.unlink(out,()=>{})); });
});
app.use((err,_req,res,_next)=>{ if(err instanceof multer.MulterError) return res.status(413).json({ok:false,error:'حجم فایل از محدودیت سرور بیشتر است.'}); res.status(400).json({ok:false,error:err.message || 'درخواست نامعتبر است.'}); });
app.get('*', (_req,res)=>res.sendFile(path.join(__dirname,'public','index.html')));
app.listen(PORT,()=>console.log(`Suno Downloader listening on http://localhost:${PORT}`));
