BUY AGAIN / DON'T BUY AGAIN — ANDROID PLAY STORE PROJECT

SOURCE MASTER
Built separately from the confirmed master:
Buy_Again_Dont_Buy_Again_V4_1_3_TURKISH_COMPLETE_TEST.zip
The existing Cloudflare/PWA master is NOT modified.

ANDROID IDENTITY
Application/package ID: com.buyagaindontbuyagain.app
Version code: 1
Version name: 1.0.0-test
Minimum Android: API 26
Target/compile API: 36

WHAT THIS ANDROID PROJECT KEEPS
- Existing Buy Again / Don't Buy Again design
- English + Turkish interface
- Existing local item database and backup JSON structure
- Existing photos, search, categories, View Details, recent searches
- Product lookup using the existing web logic
- No ads

ANDROID-SPECIFIC ADDITIONS
- Proper Android app package/project
- Native Android Back behavior: internal history first, then exits from Home
- Proper native Close/Kapat button on Home when running inside Android app
- Native Android photo/file chooser
- Camera capture support
- Native barcode scan launch; scanned number is returned to the existing product lookup
- Native backup Save As flow for .badb.json backups
- Restore accepts the same backup file format

IMPORTANT
This is the CLOSED-TEST Android project. It does not yet contain the planned £1.99 public-release purchase/trial system.
Do not delete or alter the V4.1.3 MASTER APP ZIP.
Do not upload a first Play Store bundle until the package name and signing/upload key are deliberately confirmed, because future updates must use the same app identity/signing setup.

PHONE SAVE LOCATION
Save the ZIP supplied by ChatGPT in:
My Files > Internal storage > Documents > Buy Again App > ANDROID PLAY STORE

BUILD
Open this folder as a project in Android Studio, let Gradle sync/install the required Android SDK components, then build an Android App Bundle (AAB) for the closed test.
