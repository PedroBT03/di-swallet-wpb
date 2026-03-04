# DI-Swallet: Wallet Provider Backend (WPB)

This repository contains the implementation of the **Wallet Provider Backend (WPB)** for the EU Digital Identity Wallet architecture, as defined in the **DI-Swallet** research project.

## 🏗️ Architecture Components
- **WPI (Wallet Provider Interface):** REST API for communication with the User Domain.
- **WSCA (Wallet Secure Cryptographic Application):** Service layer managing high-assurance operations.
- **Remote WSCD (Wallet Secure Cryptographic Device):** Virtualized HSM using **SoftHSM2** via PKCS#11.

## 🛠️ Tech Stack
- **Language:** Kotlin 1.9.24
- **Framework:** Spring Boot 3.5.x
- **Build Tool:** Gradle 8.5
- **Platform:** Java 17 (LTS)
- **Standard:** PKCS#11 (SunPKCS11 Provider)

## 🚀 Prerequisites
- Ubuntu 24.04 (Noble)
- SoftHSM2: `sudo apt install softhsm2 opensc`
- OpenJDK 17: `sudo apt install openjdk-17-jdk`

## ⚙️ Environment Setup
1. Initialize the SoftHSM2 token:
   ```bash
   mkdir -p ~/softhsm/tokens
   echo "directories.tokendir = $HOME/softhsm/tokens" > ~/.softhsm2.conf
   softhsm2-util --init-token --free --label "DI-Swallet-WSCD" --pin 1234 --so-pin 123456
   ```
2. Set the environment variable:
   ```bash
   export SOFTHSM2_CONF=$HOME/.softhsm2.conf
   ```

## 💻 Running the Application
```bash
./gradlew :app:bootRun
```

## 🧪 Testing the API
Generate a hardware-backed EC KeyPair (secp256r1):
```bash
curl -X POST http://localhost:8080/api/v1/wallet/keys
```