# Suno Studio

نسخه وب اصلی به‌همراه پوسته اندروید و گردش‌کار ساخت APK.

## معماری
- رابط فارسی و بک‌اند Node.js/Express حفظ شده‌اند.
- FFmpeg برای تبدیل MP3 و WAV استفاده می‌شود.
- پوسته Android با Capacitor آماده شده است.
- GitHub Actions برای ساخت APK آزمایشی در شاخه main تنظیم شده است.

## اجرای وب
نیازمند Node.js 20+ و FFmpeg:
```bash
npm install
npm start
```
سپس http://localhost:3000 را باز کنید.

## بک‌اند اندروید
APK رابط را بسته‌بندی می‌کند و برای بررسی لینک Suno و تبدیل فایل به بک‌اند HTTPS نیاز دارد. APK به‌تنهایی Node.js و FFmpeg را میزبانی نمی‌کند. برای استقرار بک‌اند، Dockerfile و render.yaml آماده شده‌اند.

## ساخت APK
از زبانه Actions گردش‌کار **Build Android APK** را اجرا کنید. پس از موفقیت، Artifact با نام Suno-Studio-debug-apk در اجرای workflow قرار می‌گیرد.

## امنیت
فقط صفحات عمومی Suno و URLهای HTTPS مجاز پذیرفته می‌شوند. این ابزار برای دور زدن حساب، پرداخت، محتوای خصوصی یا DRM طراحی نشده است.
