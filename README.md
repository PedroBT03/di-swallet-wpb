# DI-Swallet: Wallet Provider Backend (WPB)

This repository contains the implementation of the **Wallet Provider Backend (WPB)** for the EU Digital Identity Wallet architecture, as defined in the **DI-Swallet** research project at **Instituto Superior Técnico**.

## 🏗️ Architecture Components
- **WPI (Wallet Provider Interface):** REST API for communication with the User Domain.
- **WSCA (Wallet Secure Cryptographic Application):** Service layer managing high-assurance operations.
- **Remote WSCD (Wallet Secure Cryptographic Device):** Virtualized HSM using **SoftHSM2** via PKCS#11.
- **Key Metadata Store:** Persistence layer for tracking cryptographic assets and user associations.

## 🛠️ Tech Stack
- **Language:** Kotlin 1.9.24
- **Framework:** Spring Boot 3.5.x
- **Persistence:** Spring Data JPA with H2 (In-Memory)
- **Build Tool:** Gradle 8.5
- **Platform:** Java 17 (LTS)
- **Standard:** PKCS#11 (SunPKCS11 Provider)

## 🚀 Prerequisites
- Ubuntu 24.04 (Noble)
- SoftHSM2: `sudo apt install softhsm2 opensc`
- OpenJDK 17: `sudo apt install openjdk-17-jdk`

## ⚙️ Environment Setup
1. Initialize the SoftHSM2 token (Run once):
   ```bash
   mkdir -p ~/softhsm/tokens
   echo "directories.tokendir = $HOME/softhsm/tokens" > ~/.softhsm2.conf
   echo "objectstore.backend = file" >> ~/.softhsm2.conf
   softhsm2-util --init-token --free --label "DI-Swallet-WSCD" --pin 1234 --so-pin 123456
   ```

## 💻 Running the Application
The `SOFTHSM2_CONF` variable is automatically handled by the Gradle build script.
```bash
./gradlew :app:bootRun
```

## 🧪 API Reference

### Generate User Key
Generates a hardware-backed EC KeyPair (secp256r1) inside the HSM and stores the metadata in the database.
- **Endpoint:** `POST /api/v1/wallet/keys/{userId}`
- **Example:**
  ```bash
  curl -X POST http://localhost:8080/api/v1/wallet/keys/pedro-ist
  ```

### Database Inspection
You can inspect the stored key metadata via the H2 Console:
- **URL:** `http://localhost:8080/h2-console`
- **JDBC URL:** `jdbc:h2:mem:testdb`
- **User:** `sa` | **Password:** (empty)