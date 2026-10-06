# Castle Terminal Key Injection Guide

## Overview

Castle terminals use a secure key management system (KMS2) that requires keys to be loaded in encrypted form. This document explains how key injection works and how to set up keys for PIN encryption in the Cashless ATM application.

## Key Concepts

### Two-Stage Key Loading

Castle terminals use a two-stage key loading process:

1. **Stage 1: KEK (Key Encryption Key)** - Initial Master Key
   - Loaded ONCE in plain text via the Key Injection Tool
   - Typically stored at KeySet=0xCFFF, KeyIndex=0x0000
   - Used to encrypt/protect all subsequent working keys
   - Must be loaded before any working keys can be injected

2. **Stage 2: Working Keys** - Encrypted Format Only
   - PIN encryption keys, MAC keys, data encryption keys
   - Must be encrypted under the KEK using TR-31 or CKBB format
   - Cannot be loaded in plain text (security requirement)

### Why Clear Keys Cannot Be Loaded

Castle terminals are PCI-PTS certified payment devices. For security compliance:
- Working keys must be encrypted under a known protection key (KEK)
- The terminal verifies the key's integrity via MAC before storing
- This prevents unauthorized key injection and ensures key authenticity

## Key Formats Supported

### TR-31 Key Block Format

TR-31 (ANS TR-31) is an industry-standard key block format:

```
Header (16 bytes):
  Bytes 0-1:   Version ("B0" for TDES)
  Bytes 1-4:   Block length (ASCII, e.g., "0096")
  Bytes 5-6:   Key usage (P0=PIN, D0=Data, K1=KEK, M0=MAC)
  Byte 7:      Algorithm ("T" = TDES)
  Byte 8:      Mode of use (E=encrypt, D=decrypt, G=MAC generate)
  Bytes 9-10:  Key version number
  Byte 11:     Exportability ("N" = non-exportable)
  Bytes 12-15: Reserved

Encrypted Key Data:
  Random padding + Key value, encrypted with KBEK (Key Block Encryption Key)

MAC:
  CMAC computed with KBMK (Key Block MAC Key)
```

Example TR-31 block:
```
B0080P0TE00N000089BDB5FAC1398398A2C7B5D4E8F0A1B2C3D4E5F6A7B8C9D0E1F2...
```

### CKBB Format (Castles Key Block)

Castle's proprietary format, similar to TR-31 but with Castle-specific extensions.

## Key Injection Tool

Castle provides a Windows-based Key Injection Tool (KIT.exe) for loading keys.

### Location
```
/Users/mbroadbent/Documents/Castle/Android SDK/Tools/Key Injection Tool/
  extracted/Key Injection Tool V2.01/utility/key-injection-tool_v2.01/KIT.exe
```

### Documentation
```
/Users/mbroadbent/Documents/Castle/Android SDK/Tools/Key Injection Tool/
  extracted/Key Injection Tool V2.01/doc/Key Injection Tool User Munual v1.4.pdf
```

### Key Injection Tool Features

1. **Initial Key (KEK) Tab**
   - Load the first plain-text KEK
   - Supports component keys (split into parts for dual control)
   - Calculates Key Check Value (KCV) for verification

2. **Working Key by TR31 Tab**
   - Load encrypted working keys
   - Reference the KEK used for encryption
   - Support for multiple keys in sequence

3. **Generate Key Block Tab**
   - Helper to create TR-31 blocks
   - Input KEK and working key values
   - Select key type and attributes
   - Generates ready-to-load key block

## KMS2 API Reference

### Key Classes

| Class | Purpose |
|-------|---------|
| `CtKMS2System` | System initialization |
| `CtKMS2Key` | Key info/selection (read-only) |
| `CtKMS2TR31` | TR-31 key block loading |
| `CtKMS2CKBB` | CKBB key block loading |
| `CtKMS2FixedKey` | Fixed key encryption/PIN |
| `CtKMS2MKSK` | Master/Session key operations |
| `CtKMS2Dukpt` | DUKPT key management |

### Loading a TR-31 Key (Java)

```java
import CTOS.CtKMS2TR31;
import CTOS.CtKMS2Exception;

// Assumes KEK is already loaded at kekKeySet/kekKeyIndex
public boolean loadTr31Key(String tr31Block, int targetKeySet, int targetKeyIndex) {
    try {
        CtKMS2TR31 tr31 = new CtKMS2TR31();

        // Select the KEK (protection key)
        tr31.selectKey(kekKeySet, kekKeyIndex);

        // Set destination for unwrapped key
        tr31.setKeyLocation(targetKeySet, targetKeyIndex);

        // Set the TR-31 key block
        byte[] keyBlockBytes = tr31Block.getBytes("ISO-8859-1");
        tr31.setTR31KeyBlock(keyBlockBytes, 0, keyBlockBytes.length);

        // Decrypt and store the key
        tr31.writeKey();

        return true;
    } catch (CtKMS2Exception e) {
        Log.e(TAG, "TR-31 load failed: " + String.format("0x%08X", e.getError()));
        return false;
    }
}
```

### Common Key Locations

| Purpose | KeySet | KeyIndex | Notes |
|---------|--------|----------|-------|
| TMK (Terminal Master Key) | 0xC000 | 0x0001 | Protection key for working keys |
| Online PIN Key | 0xC001 | 0x00A1 | Default location for PIN encryption |
| MAC Key | 0xC001 | 0x00A2 | Message authentication |
| KEK (Key Encryption Key) | 0xCFFF | 0x0000 | Initial master key |
| Test Key 1 | 0x0001 | 0x0001 | Common test location |
| Sample Code Key | 0x00F1 | 0x0022 | Used in Castle samples |

### Key Attributes

```java
// Key type values
0x01 = DES (single length)
0x02 = TDES-112 (double length, 16 bytes)
0x03 = TDES-168 (triple length, 24 bytes)
0x11 = AES-128
0x12 = AES-192
0x13 = AES-256

// Key attribute flags
0x0001 = PIN encryption
0x0002 = Data encryption
0x0004 = MAC generation
0x0008 = Key Protection Key (KPK)
0x0010 = Data decryption
0x0020 = Key Block Protection Key (KBPK)
```

## Setting Up Test Keys

### Option 1: Use Key Injection Tool (Recommended)

1. Connect terminal via USB
2. Run KIT.exe on Windows
3. Load KEK first (Initial Key tab)
4. Generate TR-31 block for PIN key (Generate Key Block tab)
5. Load PIN key (Working Key by TR31 tab)

### Option 2: Factory Reset + Pre-loaded Keys

Some Castle terminals come with test keys pre-loaded. After factory reset:
1. SATURN1000: Settings → Factory Reset (password: "00000000")
2. Check if test keys exist using the "Scan Keys" feature in admin

### Option 3: Remote Key Injection

For production deployments:
1. Use a Key Injection Facility (KIF)
2. Keys are loaded at manufacturing/deployment
3. Working keys downloaded via host protocol (TR-31 format)

## ATM Host Integration

### Current Implementation Status

The ATM host connection supports:
- Type 88 (Configuration Request) - Downloads working keys
- TR-31 key loading via `CastleKeyManager.loadTr31Key()`

### What the Server Must Provide

For key loading to work, the ATM processor must send keys in TR-31 format:

```
Field 5: TR-31 Key Block (e.g., "B0080P0TE00N0000...")
```

**Current Issue**: The test server sends keys in clear/pipe-separated format:
```
A7A26D6BA770F8AB|C201D34932D6F15B
```

This format cannot be loaded because:
1. No KEK/TMK exists on the terminal
2. Clear keys cannot be injected directly (security)

### Solution Options

1. **Server sends TR-31 format** - Requires KEK on terminal + server HSM
2. **Pre-load test keys** - Use Key Injection Tool
3. **Factory key** - Check if any keys exist after factory reset

## Troubleshooting

### Error: Key not found (0x00002905)
- No key exists at the specified location
- Use "Scan Keys" in admin to find available keys
- Load keys using Key Injection Tool

### Error: Invalid key block
- TR-31 block is malformed or uses wrong KEK
- Verify KEK matches what was used to encrypt
- Check TR-31 header format

### Error: Key verification failed
- MAC check failed - key block was tampered or wrong KEK
- Re-generate TR-31 block with correct KEK

## References

- Key Injection Tool User Manual v1.4 (in doc/ folder)
- Castle KMS2 API Reference Manual
- ANS TR-31 Key Block Specification
- PCI PIN Security Requirements

## File Locations

| Resource | Path |
|----------|------|
| Key Injection Tool | `Android SDK/Tools/Key Injection Tool/extracted/Key Injection Tool V2.01/utility/` |
| KMS2 Sample Code | `CTOS_SDK_Installed/SATURN7000/examples/KMS2sample/` |
| TR-31 Generator (Java) | `Android SDK/Tools/Key Injection Tool/extracted/.../resource/SATURN series/` |
| KMS2 SDK JAR | `Emvtxn-S1F4/app/libs/CTOS.CtKMS2_3.4.0.jar` |
