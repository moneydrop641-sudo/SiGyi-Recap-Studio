# Termux မှ GitHub တင်ပြီး APK Build လုပ်ရန်

ဒီ project ကို Termux မှာ source အဖြစ် push လုပ်ပြီး GitHub Actions က Android Debug APK ကို build ပေးမည်။ Termux ထဲမှာ Android SDK/Gradle တပ်ဆင်ရန် မလိုပါ။

## 1) ZIP ဖြည်ပြီး project folder ဝင်ပါ

```bash
pkg update -y
pkg install git unzip nodejs-lts -y
unzip SiGyi_Recap_Studio_FINAL_GITHUB_ACTIONS.zip
cd sigyi_work
```

## 2) GitHub မှာ repository အသစ်ဖန်တီးပါ

GitHub website မှာ ဥပမာ `sigyi-recap-studio` ဆိုတဲ့ **empty repository** တစ်ခုဖန်တီးပါ။ README, license, .gitignore ကို GitHub ဘက်ကနေ ကြိုမဖန်တီးပါနှင့်။

## 3) Termux မှ push လုပ်ပါ

`USERNAME` နဲ့ `REPOSITORY` ကို ကိုယ့် GitHub အချက်အလက်နဲ့ အစားထိုးပါ။

```bash
git init
git branch -M main
git add .
git commit -m "Si Gyi Recap Studio final source"
git remote add origin https://github.com/USERNAME/REPOSITORY.git
git push -u origin main
```

GitHub က password အစား Personal Access Token (PAT) တောင်းနိုင်သည်။ Token ကို chat ထဲ မပို့ပါနှင့်၊ source code ထဲ မရေးပါနှင့်။

## 4) APK ရယူရန်

GitHub repository → **Actions** → **Android APK Build** ကိုဖွင့်ပါ။ Workflow အောင်မြင်ပြီးပါက run ထဲက **Artifacts** အောက်တွင် `SiGyi-Recap-Studio-debug-apk` ကို download လုပ်ပါ။ ZIP ကိုဖြည်ပြီး `app-debug.apk` ကို Android ဖုန်းမှာ install လုပ်ပါ။

## Build အကြောင်း သိထားရန်

- ဒီ workflow သည် Debug APK ထုတ်ပေးသည်။ Play Store အတွက် signed release APK/AAB မဟုတ်ပါ။
- ပထမဆုံး GitHub Actions run မှာ Android SDK/Gradle/Node dependencies ကို download လုပ်မည်ဖြစ်သောကြောင့် အချိန်ယူနိုင်သည်။
- Gemini Google Login ကို embedded WebView ထဲမှာ Google က လုံခြုံရေးကြောင့် ပိတ်ထားနိုင်သည်။ Build အောင်မြင်ခြင်းသည် Gemini Login/Download ကို ဖုန်းပေါ်မှာ အောင်မြင်ကြောင်း အာမခံခြင်းမဟုတ်ပါ။
- WAV/SRT/Video download auto-import နှင့် audio/subtitle timing ကို physical Android device မှာ သီးခြားစမ်းသပ်ရမည်။
