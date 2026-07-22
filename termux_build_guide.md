# How to Build MiniBrowser inside Termux on Android 📱

This guide explains how to compile the **MiniBrowser** Android project directly on your Android device using **Termux**. 

Because standard Android SDK binaries (like `aapt2` or `apksigner`) are compiled for x86_64 architectures, trying to run them natively on ARM64 Android devices will crash. We solve this by overriding the x86 binaries with native ARM64 compilation tools.

We provide **two highly reliable methods** to build this project:
1. **Method 1: Native Termux Build** (Recommended — Fast, lightweight, and uses less disk space).
2. **Method 2: Ubuntu PRoot Container Build** (Recommended if you want a standard, isolated Ubuntu Linux compilation environment).

---

## ⚡ Method 1: Native Termux Build (Fast & Lightweight)

This method uses Termux's native package repositories and a pre-configured Android SDK downloader.

### Step 1: Install Termux
Ensure you have the latest Termux app installed from **F-Droid** (do NOT use the Play Store version, as it is heavily deprecated and no longer receives updates).

### Step 2: Update Packages & Setup Storage
Open Termux and run the following commands to update repositories and grant Termux storage permissions:
```bash
pkg update && pkg upgrade -y
termux-setup-storage
```

### Step 3: Install Core Dependencies
Install Java 17, Gradle, Git, and native Android compilation helpers (`aapt`/`aapt2`):
```bash
pkg install git wget curl zip unzip openjdk-17 gradle aapt aapt2 -y
```

### Step 4: Clone the Termux Helper & Setup Android SDK
To easily bootstrap a fully compliant Android SDK, we clone the Termux packages repository and run their automated SDK installer script:
```bash
# Clone the termux packages script helper
git clone --depth 1 https://github.com/termux/termux-packages.git

# Run their SDK setup script (installs SDK at $HOME/lib/android-sdk)
export TERMUX_PKG_TMPDIR=$PREFIX/tmp
export TERMUX_JAVA_HOME=$PREFIX/lib/jvm/java-17-openjdk
./termux-packages/scripts/setup-android-sdk.sh
```

### Step 5: Configure Environment Variables
Set the environment variables so Gradle knows where to look for the Android SDK. Append them to your `.bashrc` or `.profile` so they persist:
```bash
echo 'export ANDROID_HOME=$HOME/lib/android-sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/platform-tools' >> ~/.bashrc
source ~/.bashrc
```

### Step 6: Configure Native AAPT2 Override
We must instruct Gradle to bypass the x86 `aapt2` binary packed inside Google's maven dependency and use Termux's native ARM64 `aapt2` binary instead:
```bash
mkdir -p ~/.gradle
echo "android.aapt2FromMavenOverride=$PREFIX/bin/aapt2" > ~/.gradle/gradle.properties
```

### Step 7: Clone and Build MiniBrowser
Now, clone your repository and compile the debug APK:
```bash
# Clone your repository
git clone https://github.com/Devcode940/MiniBrowser.git
cd MiniBrowser

# Feed the SDK location to your local.properties
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Compile the project
./gradlew assembleDebug
```

---

## 🐳 Method 2: Ubuntu PRoot Container Build (Isolated & Highly Compatible)

This method sets up a sandboxed **Ubuntu Linux container** inside Termux. This mirrors a full desktop compilation suite, making it highly robust.

### Step 1: Install PRoot-Distro
Inside Termux, install the distro manager and spin up an Ubuntu container:
```bash
pkg update && pkg upgrade -y
pkg install proot-distro -y
proot-distro install ubuntu
```

### Step 2: Login to Ubuntu Container
Run this to log in as root inside your newly isolated Ubuntu shell:
```bash
proot-distro login ubuntu
```

### Step 3: Install Core Compilation Utilities
Inside the Ubuntu shell, update packages and install standard OpenJDK 17, Git, Gradle, and ARM64-compiled `aapt`/`aapt2`:
```bash
apt update && apt upgrade -y
apt install -y openjdk-17-jdk gradle wget unzip git aapt2 aapt
```

### Step 4: Download and Extract Command Line SDK Tools
Download and configure the official command line package from Google:
```bash
cd ~
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip

# Rearrange directory structure to match Google's specifications
mkdir -p android-sdk/cmdline-tools
mv cmdline-tools android-sdk/cmdline-tools/latest
```

### Step 5: Configure Environment Variables
Set up paths to expose the SDK managers and Java variables. Append them to `~/.bashrc`:
```bash
cat >> ~/.bashrc << 'EOF'
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_HOME/platform-tools
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
EOF

source ~/.bashrc
```

### Step 6: Install Android SDK platforms & Build-Tools
Accept Google’s licenses and download required packages using `sdkmanager`:
```bash
# Accept Licenses
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses

# Download Platform components
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

### Step 7: Override AAPT2 and Build
Instruct Gradle to use the native Ubuntu ARM64 `aapt2` binary instead of Maven’s x86 binary:
```bash
mkdir -p ~/.gradle
echo "android.aapt2FromMavenOverride=/usr/bin/aapt2" > ~/.gradle/gradle.properties
```

Now, clone the repository and build:
```bash
git clone https://github.com/Devcode940/MiniBrowser.git
cd MiniBrowser

# Bind SDK directory
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Build debug APK
./gradlew assembleDebug
```

---

## 📦 Locating and Installing Your APK

Once the compilation completes successfully, your APK will be available at:
`app/build/outputs/apk/debug/app-debug.apk`

### Copy APK to Your Device Downloads
If you are compiling using **Method 1 (Native)**, copy the APK directly to your phone's standard Downloads folder:
```bash
cp app/build/outputs/apk/debug/app-debug.apk /storage/emulated/0/Download/
```

If you are using **Method 2 (Ubuntu PRoot)**, copy it through the termux host bindings:
```bash
# Copy to Termux user directory
cp app/build/outputs/apk/debug/app-debug.apk /data/data/com.termux/files/home/app-debug.apk

# Log out of Ubuntu
exit

# Copy from Termux home to Phone Downloads
cp ~/app-debug.apk /storage/emulated/0/Download/
```

Open your phone's **Files App**, navigate to the **Downloads** directory, tap **`app-debug.apk`**, and install your custom, advanced **MiniBrowser**! 🎉
