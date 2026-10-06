# Castle Key Injection Tool V2.01 - User Guide

## Overview

The Key Injection Tool V2.01 is a Windows application for injecting cryptographic keys into Castle payment terminals. This guide covers injecting DUKPT IPEK keys for ATM PIN encryption.

**Location:** `Android SDK/Tools/Key Injection Tool/extracted/Key Injection Tool V2.01/utility/key-injection-tool_v2.01/key_injection_tool.exe`

**Requirements:**
- Windows PC (or Parallels/VM on Mac)
- USB connection to terminal
- Terminal in download mode

---

## Tool Interface

### Top Section (Always Visible)

```
┌─────────────────────────────────────────────────────────────┐
│ COM                    │ Log Window                         │
│ [COM7 ▼] [Refresh]     │ [10:09:16] Get Device Type Success │
│                        │ [10:09:16] Open COM6 Success       │
│ Key-Store              │ [10:09:17] The KEK Loaded Success  │
│ [TR31 ▼] [Clear]       │                                    │
└─────────────────────────────────────────────────────────────┘
```

| Field | Description |
|-------|-------------|
| **COM** | Select COM port for USB connection |
| **Refresh** | Scan for available COM ports |
| **Key-Store** | Key block format (TR31 recommended) |
| **Clear** | Clear log window |
| **Log Window** | Shows operation status messages |

---

## Tabs Overview

### Tab 1: Initial Key (KEK)

**Purpose:** Inject the master Key Encryption Key (KEK/TMK)

```
┌─────────────────────────────────────────────────────────────┐
│ KEK Key: [3DES ▼]                                           │
│ Key Set: [C001]     Key Index: [00A1]                       │
│                                                              │
│ ☑ Use Components                                            │
│ Key Com #1: [****************] 32 digits [Check Value] F0AA │
│ Key Com #2: [****************] 32 digits [Check Value] 5A5F5│
│                                    [Check Value] C6611      │
│                                              [Inject]       │
└─────────────────────────────────────────────────────────────┘
```

| Field | Description |
|-------|-------------|
| **KEK Key** | Key type (3DES) |
| **Key Set** | Hex key set location (e.g., C001, CFFF) |
| **Key Index** | Hex key index (e.g., 00A1, 0000) |
| **Use Components** | Enable split-knowledge key entry |
| **Key Com #1** | First key component (16 bytes / 32 hex) |
| **Key Com #2** | Second key component (16 bytes / 32 hex) |
| **Check Value** | KCV for each component and combined key |
| **Inject** | Write key to terminal |

**Usage:** For injecting TMK/KEK at location like CFFF/0000 or C001/00A1

---

### Tab 2: Working Key

**Purpose:** Manage working keys encrypted under KEK

```
┌─────────────────────────────────────────────────────────────┐
│ ☑ Non Lock                                                  │
│                                                              │
│ │ KEK KI │ Working KS │ Working KI │ Owner Name │ Key │    │
│ │ 0000   │ C001       │ 00A1       │            │     │    │
│                                                              │
│ [Add] [Delete]                              [Inject]        │
│ [Duplicate] [Lock]                                          │
└─────────────────────────────────────────────────────────────┘
```

| Field | Description |
|-------|-------------|
| **Non Lock** | Key is not locked to device |
| **KEK KI** | KEK Key Index used to encrypt working key |
| **Working KS** | Working key set location |
| **Working KI** | Working key index |
| **Owner Name** | Optional identifier |
| **Add/Delete** | Add or remove entries |
| **Inject** | Write working key to terminal |

---

### Tab 3: Generate Key Block

**Purpose:** Generate TR31 key blocks for key exchange

```
┌─────────────────────────────────────────────────────────────┐
│ TR31 Key Block : KEK Key Block : 3DES                       │
│ KEK Com #1: [                    ] 00 digits [Check Value]  │
│ KEK Com #2: [                    ] 00 digits [Check Value]  │
│                                              [Check Value]  │
│                                                              │
│ Working Key: [                    ] 00 digits [Check Value] │
│ KSN:         [                    ] 00 digits               │
│                                                              │
│ Working Key Type: [3DES ▼]  ☐ Key Component Export          │
│ Working Key Usage: [PIN Enc ▼]                              │
│                                              [Generate]     │
│                                    Paste to: [1 ▼]          │
└─────────────────────────────────────────────────────────────┘
```

| Field | Description |
|-------|-------------|
| **KEK Com #1/#2** | KEK components for key block generation |
| **Working Key** | The key to wrap in TR31 block |
| **KSN** | Key Serial Number (for DUKPT) |
| **Working Key Type** | 3DES, AES, etc. |
| **Working Key Usage** | PIN Encryption, Data Encryption, etc. |
| **Generate** | Create TR31 key block |

---

### Tab 4: TLS PKC

**Purpose:** TLS/Secure Channel certificate management

```
┌─────────────────────────────────────────────────────────────┐
│ Supported: [Secure Channel ▼]     ☐ Non Lock               │
│                                                              │
│ PKC Location: [/home/ap/pub/]                               │
│                                                              │
│ SK                                                           │
│ Key Set: [10]                                                │
│ Key Index: [001]                                             │
│                                              [Inject]       │
└─────────────────────────────────────────────────────────────┘
```

**Usage:** For injecting TLS certificates and secure channel keys

---

### Tab 5: Information

**Purpose:** Query terminal and key information

```
┌─────────────────────────────────────────────────────────────┐
│ Get Device Info        │ Key Information                    │
│      [Get]             │ KeySet: [    ]                     │
│                        │ KeyIndex: [    ]                   │
│ Current Life Cycle     │           [Get]                    │
│      [Get]             │                                    │
│                        │                                    │
│ Lock Device            │                                    │
│      [Lock]            │                                    │
└─────────────────────────────────────────────────────────────┘
```

| Button | Description |
|--------|-------------|
| **Get Device Info** | Query terminal serial, firmware, etc. |
| **Current Life Cycle** | Check terminal security state |
| **Key Information** | Query specific key by set/index |
| **Lock Device** | Lock terminal (CAUTION: irreversible!) |

---

### Tab 6: WK with Geobridge (DUKPT)

**Purpose:** Inject DUKPT IPEK keys via Geobridge HSM format

```
┌─────────────────────────────────────────────────────────────┐
│ │ Key Index    │ TR31 Key Block                        │   │
│ │ DUKPT(1) ▼   │ [                                   ] │   │
│                                                              │
│ [Add] [Delete]                              [Inject]        │
└─────────────────────────────────────────────────────────────┘
```

| Field | Description |
|-------|-------------|
| **Key Index** | Select DUKPT(1), DUKPT(2), etc. |
| **TR31 Key Block** | TR31-wrapped IPEK |
| **Inject** | Write DUKPT key to terminal |

**This is the tab for DUKPT IPEK injection!**

---

### Tab 7: WK with FutureX

**Purpose:** Inject keys via FutureX HSM format

```
┌─────────────────────────────────────────────────────────────┐
│ │ Key Type       │ Key Slot │ Key Value │ Key Len │ KCV │  │
│ │ Master Session │ 10       │           │ 00      │     │  │
│                                                              │
│ [Add] [Delete]                              [Inject]        │
└─────────────────────────────────────────────────────────────┘
```

| Field | Description |
|-------|-------------|
| **Key Type** | Master Session, PIN Key, etc. |
| **Key Slot** | Slot number in HSM |
| **Key Value** | Encrypted key value |
| **Inject** | Write key to terminal |

---

## DUKPT IPEK Injection Procedure

### Prerequisites
- Terminal connected via USB
- Terminal in download mode
- COM port identified (use Refresh button)

### Step 1: Connect to Terminal

1. Select COM port from dropdown (e.g., COM7)
2. Click **Refresh** if port not visible
3. Log should show: `Get Device Type Successfully`

### Step 2: Navigate to DUKPT Tab

1. Click the **"WK with Geobridge"** tab
2. This tab supports DUKPT key injection

### Step 3: Configure DUKPT Key

**Option A: Using TR31 Key Block (Recommended)**

If you have a TR31-wrapped IPEK from an HSM:
1. Select **DUKPT(1)** from Key Index dropdown
2. Paste TR31 key block into the field
3. Click **Inject**

**Option B: Using Initial Key (KEK) Tab for Clear Key**

If injecting clear IPEK (development/test only):
1. Go to **Initial Key (KEK)** tab
2. Set Key Set: `C002`
3. Set Key Index: `00A1`
4. Uncheck "Use Components" for single key entry
5. Enter IPEK value: `6AC292FAA1315B4D858AB3A3D7D5933A`
6. Click **Check Value** to verify KCV = `27E4A3`
7. Click **Inject**

### Step 4: Verify Injection

1. Go to **Information** tab
2. Enter KeySet: `C002`, KeyIndex: `00A1`
3. Click **Get**
4. Verify key exists at location

---

## Key Locations Reference

| Purpose | Key Set | Key Index | Key Type |
|---------|---------|-----------|----------|
| TMK/KEK (Master) | CFFF | 0000 | 3DES KEK |
| Working Key (Static) | C001 | 00A1 | 3DES PIN |
| **DUKPT IPEK** | **C002** | **00A1** | **DUKPT** |

---

## Test Keys (Development Only)

**DO NOT USE IN PRODUCTION**

| Key | Value | KCV |
|-----|-------|-----|
| Test BDK | `0123456789ABCDEFFEDCBA9876543210` | `08D7B4` |
| Test IPEK | `6AC292FAA1315B4D858AB3A3D7D5933A` | `AF8C07` |
| Test KSN | `FFFF9876543210E00001` | - |

---

## Troubleshooting

### "Cannot open COM port"
- Terminal not in download mode
- Wrong COM port selected
- USB not passed through to VM (Parallels)

### "Key injection failed"
- Key format incorrect (must be 32 hex chars)
- Key location already locked
- Terminal in wrong lifecycle state

### "KCV mismatch"
- Key value entered incorrectly
- Using wrong key components

### "Device locked"
- Terminal has been locked
- Contact Castle support

---

## Security Notes

1. **Never inject production keys on development machines**
2. **Use split-knowledge (components) for production keys**
3. **Verify KCV before injection**
4. **Document all key injections in audit log**
5. **Clear keys from test terminals before deployment**
