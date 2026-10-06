# DUKPT Track 2 Encryption Issue - Investigation Summary

**Date**: December 22, 2025
**Status**: UNRESOLVED - SDK masks data before encryption
**Priority**: CRITICAL - Blocking ATM processor integration

## The Problem

The Castle SDK masks sensitive card data (PAN, Track 2) for PCI compliance **BEFORE** the application can access it. This means:

1. When we encrypt Track 2 with DUKPT, we're encrypting already-masked data
2. Processor decrypts and gets: `;443041******8318=2605201?` (masked)
3. Processor cannot identify the card or translate PIN blocks

## What We Tried

### 1. SDK secureDataEncryptInfoSet (FAILED)

Configured the SDK to encrypt data at the hardware level:

```java
// In thInit (MainActivity.java lines 2839-2857)
EMVSecureDataInfo emvSecureInfo = new EMVSecureDataInfo();
emvSecureInfo.version = 4;
emvSecureInfo.keyType = (byte) 2;  // DUKPT key
emvSecureInfo.cipherKeySet = 0xC001;
emvSecureInfo.cipherKeyIndex = 0x001A;
emvSecureInfo.cipherMethod = 0x01;  // CBC
emvSecureInfo.ICVLen = 8;
emvSecureInfo.ICV = new byte[8];
emvSecureInfo.paddingMethod = 1;
emv.secureDataEncryptInfoSet(emvSecureInfo);  // Returns SUCCESS
```

**Result**: Setup returns OK, but **DF33 (encrypted track) is always empty** (len=0)

### 2. Reading Tags 5A and 57 Directly (FAILED)

Tried to get raw PAN (5A) and Track 2 (57) from EMV:

```java
TlvData data = new TlvData();
data.tag = 0x57;  // Track 2
int result = emv.dataGet(data);
// Result: 0x00000108 (blocked/not available)
```

**Result**: SDK returns error 0x00000108 - tags are blocked

### 3. Reading DF35 (Clear Track 2 Equivalent) (FAILED)

```java
data.tag = 0xDF35;
int result = emv.dataGet(data);
// Returns data, but it's already masked!
```

**Result**: Returns `;443041******8318=2605201?` - still masked

### 4. Manual DUKPT Encryption (WORKS - but encrypts masked data)

```java
CtKMS2Dukpt dukpt = new CtKMS2Dukpt();
dukpt.selectKey(0xC001, 0x001A);
dukpt.setCipherMethod(CtKMS2Dukpt.DATA_ENCRYPT_METHOD_CBC);
dukpt.setICV(new byte[8], 0, 8);
dukpt.setInputData(track2Data.getBytes(), 0, length);
dukpt.dataEncrypt();
// SUCCESS - but track2Data is already masked
```

**Result**: Encryption works, but we're encrypting masked data

## Key Findings

### 1. DUKPT Key is Working
- IPEK injected at C001/001A
- KCV: af8c07
- KSN incrementing correctly (currently at 0x22)
- Data encryption/decryption verified working

### 2. SDK Masking is Absolute
The Castle SDK masks sensitive data at the lowest level:
- Tags 5A, 57, DF35 all return masked data
- DF33 (encrypted track) is not populated
- Error 0x00000108 when requesting raw sensitive tags

### 3. CastlesHost Sample Code Analysis
Looking at `/CastlesHost_SampleApp_TSYS-Sierra-14C/`:

```java
// PollCard.java lines 461-474
reqData.tagList = new byte[]{0x5A, 0x57};
result = emv.dataGetEx(reqData);
if (result == 0) {
    encryptEMVData(emvData);  // They encrypt tags 5A/57
}
```

They successfully read tags 5A/57 and encrypt them. **Why does it work for them but not us?**

Possible differences:
1. Different SDK version
2. Different terminal configuration
3. Different security/PCI mode
4. Missing whitelist configuration

### 4. Whitelist Configuration
Our current whitelist setup might be wrong:

```java
// Current code (line 3091)
emv.secureDataWhitelistSet((byte) 0x01, new byte[4], 4);  // action=0x01 = delete?
```

CastlesHost sample doesn't appear to use whitelist. Need to investigate proper whitelist usage.

## Configuration Comparison

| Parameter | Our Config | CastlesHost Sample |
|-----------|------------|-------------------|
| version | 4 | 2 |
| keyType | 2 (DUKPT) | TDES_DUKPT enum |
| cipherMethod | 0x01 (CBC) | CipherMethod.CBC |
| checksumType | 0 | SHA1 |
| ICVLen | 8 | 16 |
| paddingMethod | 1 | 1 |

## Files Modified

1. **MainActivity.java**
   - Added EMV `secureDataEncryptInfoSet` in thInit (lines 2839-2857)
   - Added EMVCL `secureDataEncryptInfoSet` in thInit (lines 2888-2909)
   - Updated MSR to use ATM DUKPT key (lines 2924-2946)
   - Added `encryptTrack2WithDukpt()` helper method (lines 6281-6356)

2. **TransactionRequest.java**
   - Added `encryptedTrack2` field
   - Added `track2Ksn` field
   - Added `hasEncryptedTrack2()` method

3. **HyosungMessageBuilder.java**
   - Field 6: `e{EncryptedTrack2}` (with 'e' prefix)
   - Field 7: `{KSN}` (separate from encrypted data)

4. **AtmTransactionManager.java**
   - Added retry logic for key download (3 retries)
   - Passes encrypted track and KSN to request

5. **GlobalPara.java**
   - `atmDukptKeySet = 0xC001`
   - `atmDukptKeyIndex = 0x001A`

## Current Message Format

```
Field 6: e{64-char hex encrypted track 2}
Field 7: {20-char KSN}
Field 8: {16-char PIN block}
```

Example:
```
Field 6: e0F8976A2AAD6CCE4CE5CE464411098A0727C3353FE78A8388D6D34C6C6F93A79
Field 7: FFFF9876543210E00022
Field 8: 73DE811060325A11
```

## Next Steps to Investigate

1. **Contact Castle Support** - Ask how to get unmasked data for DUKPT encryption
   - Is there a special terminal configuration?
   - Is there a different API to use?
   - Why does CastlesHost sample work?

2. **Check Terminal Security Mode** - Terminal might be in P2PE mode
   - How to check current security mode?
   - How to configure for DUKPT data encryption?

3. **Try secureDataWhitelistSet** properly
   - What format should the whitelist be?
   - action=0x00 for set vs 0x01 for delete?

4. **Compare SDK JAR versions**
   - Our SDK JARs vs CastlesHost SDK JARs
   - Any API differences?

5. **Check if different key location needed**
   - Maybe IPEK needs to be in a different slot?
   - Check if there's a dedicated "data encryption" key slot

## DUKPT Test Vectors

For processor to verify decryption:

| Item | Value |
|------|-------|
| IPEK | 6AC292FAA1315B4D858AB3A3D7D5933A |
| BDK | 0123456789ABCDEFFEDCBA9876543210 |
| KSN Base | FFFF9876543210E00000 |
| Current Counter | ~0x22 (34 decimal) |
| Cipher | 3DES CBC |
| IV | 00 00 00 00 00 00 00 00 |
| Padding | PKCS5/7 |
| Key Variant | Data Encryption (XOR 0000000000FF00000000000000FF0000) |

## Error Codes Reference

| Error | Meaning |
|-------|---------|
| 0x00000108 | Tag blocked/not available |
| 0x00000000 | Success |
| DF33 len=0 | Encrypted track not populated |

## Conclusion

The Castle SDK is protecting sensitive data at a level that prevents us from encrypting unmasked track data. We need Castle's guidance on how to properly configure the terminal/SDK to allow DUKPT encryption of clear track data for ATM processor integration.

The fundamental issue is: **How do we encrypt track data BEFORE the SDK masks it?**
