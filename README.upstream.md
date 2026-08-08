## 🌐 語言 / Languages

- [🇨🇳 中文說明](README.MD)
- [🇺🇸 English Version](README.en.md)
# 你的電視：安卓電視/手机直播APK
支持安卓6.0(API23)級以上版本<br>
綜合my-tv/my-tv-0/my-tv-1/mytv-android/WebViewTVLive等項目的功能。<br>  
IPTV/網頁視頻播放安卓APK軟件，支持腾讯webview x5，<br>
可自定義源(支持webview://格式網頁視頻源)，支持手機畫中畫，IPTV支持手機熄屏播放。<br>
[yourtv](https://github.com/horsemail/yourtv)
<br>
## 在線加密解密：（兼容Tvbox的接口源加密解密）
https://yourtvcrypto.horsenma.net<br>
與項目內加密解密邏輯完全一致<br>

電報群組<br>
https://t.me/yourtvapp<br>
<img src="./screenshots/appreciate.jpg" alt="image" width=200 /><br><br>
<img src="./screenshots/527.jpg" alt="image"/><br><br>
<img src="./screenshots/090901.jpg" alt="image"/><br><br>
<img src="./screenshots/090902.jpg" alt="image"/><br><br>

注意：

* 遇到問題可以先考慮重啟/恢復默認/清除數據/重新安裝等方式自助解決

下載安裝 [releases](https://github.com/horsemail/yourtv)

## 其他

建議通過ADB進行安裝：

```shell
adb install YourTV.apk
```

小米電視可以使用小米電視助手進行安裝

## 常見問題

* 為什麼遠程配置視頻源文本後，再次打開應用後又恢復到原來的配置？<br>

  如果“應用啟動后更新視頻源”開啟後，且存在視頻源地址，則會自動更新，可能會覆蓋已保存的視頻源文本。<br>

* 自己編譯APP注意事項：<br>
  1、資源文件需要自己逐個確認設置為自己的信息，特別是cloudflare.txt/github_private.txt/sources.txt<br>
  需使用加密解密工具網站 https://yourtvcrypto.horsenma.net  加密後存儲。<br>
  2、我上傳的APK文件與源碼可能不同步，APK文件比較新，源碼更新一般落後幾天，請注意查看，<br>
  3、我上傳的APK文件使用的加密解密邏輯與項目內加密解密邏輯：https://yourtvcrypto.horsenma.net  不同，目的保護我的私有資源信息。<br>

## 感謝

[live](https://github.com/fanmingming/live)<br>
[my-tv-0](https://github.com/lizongying/my-tv-0)<br>
[my-tv-1](https://github.com/lizongying/my-tv-1)<br>

