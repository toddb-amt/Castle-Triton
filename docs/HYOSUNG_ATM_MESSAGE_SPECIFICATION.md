# Hyosung (Nautilus) ATM Message Format Specification

## Document Information

| Item | Value |
|------|-------|
| Version | 2.0 |
| Last Updated | November 2025 |
| Protocol | Standard 1 (STD1) |
| Applicable Models | Hyosung/Nautilus ATM Terminals |

---

## Table of Contents

1. [Message Framing](#1-message-framing)
2. [Control Characters](#2-control-characters)
3. [Message Structure](#3-message-structure)
4. [Message Types](#4-message-types)
5. [Transaction Request (Type 85)](#5-transaction-request-type-85)
6. [Transaction Response (Type 85)](#6-transaction-response-type-85)
7. [Reversal Request (Type 86)](#7-reversal-request-type-86)
8. [Reversal Response (Type 86)](#8-reversal-response-type-86)
9. [Host Totals Request (Type 87)](#9-host-totals-request-type-87)
10. [Host Totals Response (Type 87)](#10-host-totals-response-type-87)
11. [Configuration Request (Type 88)](#11-configuration-request-type-88)
12. [Configuration Response (Type 88)](#12-configuration-response-type-88)
13. [Health Check Messages](#13-health-check-messages)
14. [Response Codes](#14-response-codes)
15. [Transaction Type Codes](#15-transaction-type-codes)
16. [Working Key Formats](#16-working-key-formats)
17. [PIN Block Format](#17-pin-block-format)
18. [EMV Data Format](#18-emv-data-format)
19. [Status Monitoring Field](#19-status-monitoring-field)
20. [Handshake Protocol](#20-handshake-protocol)
21. [Example Messages](#21-example-messages)
22. [Processor-Specific Variations](#22-processor-specific-variations)

---

## 1. Message Framing

### 1.1 Standard Framing (STX/ETX)

Used by: DNS, FIS, CDS, ITS Systems, Planet Payment, Worldpay

```
┌─────┬─────────┬────┬─────────┬────┬─────┬─────────┬─────┬─────┐
│ STX │ Field 0 │ FS │ Field 1 │ FS │ ... │ Field N │ ETX │ LRC │
└─────┴─────────┴────┴─────────┴────┴─────┴─────────┴─────┴─────┘
```

### 1.2 VISA Framing (Length Header)

Used by: Switch Commerce, Cardtronics, ASAI, EFX, Elan/Genpass, NRT/TNS

```
┌────────────────┬─────┬─────────┬────┬─────────┬────┬─────┬─────────┬─────┬─────┐
│ 2-byte Length  │ STX │ Field 0 │ FS │ Field 1 │ FS │ ... │ Field N │ ETX │ LRC │
└────────────────┴─────┴─────────┴────┴─────────┴────┴─────┴─────────┴─────┴─────┘
```

The 2-byte length header contains the total message length (excluding the length bytes themselves), stored in big-endian format.

### 1.3 Length Calculation

For VISA framing:
```
Length = (STX) + (all fields) + (all FS separators) + (ETX) + (LRC)
Length = 1 + field_data_length + (num_fields - 1) + 1 + 1
```

---

## 2. Control Characters

| Character | Hex Value | ASCII | Description |
|-----------|-----------|-------|-------------|
| STX | 0x02 | ^B | Start of Text - marks beginning of message |
| ETX | 0x03 | ^C | End of Text - marks end of message |
| FS | 0x1C | ^\ | Field Separator - delimits fields |
| ACK | 0x06 | ^F | Acknowledge - positive response |
| NAK | 0x15 | ^U | Negative Acknowledge - error response |
| EOT | 0x04 | ^D | End of Transmission - session complete |
| ENQ | 0x05 | ^E | Enquiry - connection test (optional) |

### 2.1 LRC Calculation

The Longitudinal Redundancy Check (LRC) is calculated by XORing all bytes from the byte immediately after STX up to and including ETX:

```
Algorithm:
    LRC = 0x00
    for each byte from position (STX + 1) to ETX inclusive:
        LRC = LRC XOR byte
    return LRC
```

**Example:**
```
Message: <STX>H0.000000<FS>GH001003<FS>88<FS>3<ETX>
Bytes to XOR: H, 0, ., 0, 0, 0, 0, 0, 0, FS, G, H, 0, 0, 1, 0, 0, 3, FS, 8, 8, FS, 3, ETX
```

---

## 3. Message Structure

### 3.1 Field Organization

All messages follow this general structure:

| Field Index | Description | Present In |
|-------------|-------------|------------|
| 0 | Information Header | All messages |
| 1 | Terminal ID | All messages |
| 2 | Transaction Code | All messages |
| 3+ | Message-specific data | Varies by type |

### 3.2 Information Header (Field 0)

Format: `H0.NNNNNN`

| Component | Length | Description |
|-----------|--------|-------------|
| H0. | 3 | Fixed prefix identifying Hyosung terminal |
| NNNNNN | 6 | Routing identifier (typically `000000`) |

**Processor-Specific Routing IDs:**

| Processor | Routing ID |
|-----------|------------|
| DNS | 000000 |
| FIS | 000000 |
| CDS | CDHY (in header) |
| Switch Commerce | SC101 |
| 1stISO | FSTISO |
| Cardtronics | CTSTRA |
| Worldpay | LNKATM |

### 3.3 Terminal ID (Field 1)

- Length: 6-8 characters (typically 8)
- Format: Alphanumeric
- Examples: `GH001003`, `559750`, `DNS61690`

---

## 4. Message Types

| Transaction Code | Type | Direction | Description |
|-----------------|------|-----------|-------------|
| 85 | Financial | Request/Response | Cash withdrawal, balance inquiry, transfer |
| 86 | Reversal | Request/Response | Transaction reversal |
| 87 | Host Totals | Request/Response | Settlement and totals |
| 88 | Configuration | Request/Response | Key download, configuration |
| 89 | Health Check | Request/Response | Connection verification |

---

## 5. Transaction Request (Type 85)

### 5.1 Field Layout

| Field | Name | Format | Length | Description |
|-------|------|--------|--------|-------------|
| 0 | Information Header | AN | 9 | `H0.NNNNNN` |
| 1 | Terminal ID | AN | 6-8 | Terminal identifier |
| 2 | Transaction Code | N | 2 | `85` |
| 3 | Transaction Type | A | 6 | Operation + accounts (see Section 15) |
| 4 | Sequence Number | N | 4 | `0001` - `9999`, wraps to `0001` |
| 5 | (Reserved) | - | 0 | Empty field |
| 6 | Track 2 Data | AN | 19-40 | Card data with sentinels |
| 7 | (Reserved) | - | 0 | Empty field |
| 8 | PIN Block | H | 16 | Encrypted PIN block |
| 9 | Amount | N | 1-12 | Transaction amount in cents |
| 10 | Surcharge | N | 1-12 | Surcharge amount in cents |
| 11 | Dispense Flag | N | 1 | `1` = dispense, `0` = no dispense |
| 12 | Status Monitoring | AN | Variable | Terminal status data (see Section 19) |
| 13 | EMV Data | AN | Variable | EMV chip data (see Section 18) |

### 5.2 Track 2 Data Format

```
;PPPPPPPPPPPPPPPP=YYMMSSSSSSSSSSSSS?
│                │ │  │
│                │ │  └─ Discretionary data
│                │ └─ Service code (3 digits)
│                └─ Expiration YYMM
└─ Primary Account Number (PAN)
```

| Component | Description |
|-----------|-------------|
| `;` | Start sentinel |
| PAN | Primary Account Number (13-19 digits) |
| `=` | Field separator |
| YYMM | Expiration date |
| SSS | Service code |
| Discretionary | Issuer discretionary data |
| `?` | End sentinel |

**Example:** `;4430410000008318=26052011030018610000?`

### 5.3 PIN Block

- Length: 16 hexadecimal characters (8 bytes)
- Format: ISO 9564-1 Format 0
- Encryption: 3DES under terminal working key

**Example:** `89E02BDF2751ECA7`

---

## 6. Transaction Response (Type 85)

### 6.1 Field Layout

| Field | Name | Format | Length | Description |
|-------|------|--------|--------|-------------|
| 0 | Information Header | AN | 9 | `H0.NNNNNN` |
| 1 | Terminal ID | AN | 6-8 | Terminal identifier |
| 2 | Transaction Code | N | 2 | `85` |
| 3 | Sequence Number | N | 4 | Echo of request sequence |
| 4 | Response Code | N | 2 | ISO 8583 response code |
| 5 | Authorization Data | AN | 26 | Date/time + retrieval reference |
| 6 | Settlement Data | AN | 16 | Audit trail + settlement date |
| 7 | Account Balance | N | 1-12 | Balance in cents |
| 8 | Available Balance | N | 1-12 | Available balance in cents |
| 9 | Surcharge | N | 1-12 | Applied surcharge in cents |
| 10 | Display Message | AN | 0-40 | Optional message for receipt |
| 11 | Config Indicator | N | 2 | `01` = configuration required |
| 12 | EMV Response Data | AN | Variable | EMV response tags |

### 6.2 Authorization Data (Field 5)

Format: `MMDDYYYYHHmmssRRRRRRRRRRRR`

| Position | Length | Description |
|----------|--------|-------------|
| 1-2 | 2 | Month (01-12) |
| 3-4 | 2 | Day (01-31) |
| 5-8 | 4 | Year (YYYY) |
| 9-10 | 2 | Hour (00-23) |
| 11-12 | 2 | Minute (00-59) |
| 13-14 | 2 | Second (00-59) |
| 15-26 | 12 | Retrieval Reference Number |

**Example:** `11272025100813000000000001`

### 6.3 Settlement Data (Field 6)

Format: `AAAAAASSMMDDYYYY`

| Position | Length | Description |
|----------|--------|-------------|
| 1-6 | 6 | Audit trail number |
| 7-8 | 2 | Settlement indicator (`00`) |
| 9-16 | 8 | Settlement date (MMDDYYYY) |

**Example:** `00000100112720250`

---

## 7. Reversal Request (Type 86)

### 7.1 Field Layout

| Field | Name | Format | Length | Description |
|-------|------|--------|--------|-------------|
| 0 | Information Header | AN | 9 | `H0.NNNNNN` |
| 1 | Terminal ID | AN | 6-8 | Terminal identifier |
| 2 | Transaction Code | N | 2 | `86` |
| 3 | Original Auth Data | AN | 26 | From original transaction |
| 4 | Original Sequence | N | 4 | Original sequence number |
| 5 | Track 2 Data | AN | 19-40 | Card data |
| 6 | (Reserved) | - | 0 | Empty field |
| 7 | PIN Block | H | 16 | Encrypted PIN block |
| 8 | Original Amount | N | 1-12 | Original amount in cents |
| 9 | Original Surcharge | N | 1-12 | Original surcharge in cents |
| 10 | Reversal Reason | AN | 0-2 | Optional reason code |

### 7.2 Reversal Reason Codes

| Code | Description |
|------|-------------|
| (empty) | Timeout/no response |
| 01 | Customer cancelled |
| 02 | Dispense failure |
| 03 | Partial dispense |
| 04 | Card retained |
| 05 | Host error |

---

## 8. Reversal Response (Type 86)

### 8.1 Field Layout

| Field | Name | Format | Length | Description |
|-------|------|--------|--------|-------------|
| 0 | Information Header | AN | 9 | `H0.NNNNNN` |
| 1 | Terminal ID | AN | 6-8 | Terminal identifier |
| 2 | Transaction Code | N | 2 | `86` |
| 3 | Sequence Number | N | 4 | Echo of request sequence |
| 4 | Response Code | N | 2 | `00` = accepted |
| 5 | (Reserved) | - | 0 | Empty field |

---

## 9. Host Totals Request (Type 87)

### 9.1 Field Layout

| Field | Name | Format | Length | Description |
|-------|------|--------|--------|-------------|
| 0 | Information Header | AN | 9 | `H0.NNNNNN` |
| 1 | Terminal ID | AN | 6-8 | Terminal identifier |
| 2 | Transaction Code | N | 2 | `87` |
| 3 | Reset Flag | N | 1 | `0` = query, `1` = reset after query |

---

## 10. Host Totals Response (Type 87)

### 10.1 Field Layout

| Field | Name | Format | Length | Description |
|-------|------|--------|--------|-------------|
| 0 | Information Header | AN | 9 | `H0.NNNNNN` |
| 1 | Terminal ID | AN | 6-8 | Terminal identifier |
| 2 | Transaction Code | N | 2 | `87` |
| 3 | Transaction Counts | N | 16 | Count by transaction type |
| 4 | Total Cash Dispensed | N | 1-12 | Amount in cents |
| 5 | Total Non-Cash | N | 1-12 | Amount in cents |
| 6 | Total Surcharges | N | 1-12 | Amount in cents |

### 10.2 Transaction Counts Field (Field 3)

16-character numeric string:

| Position | Length | Description |
|----------|--------|-------------|
| 1-4 | 4 | Cash Withdrawal count |
| 5-8 | 4 | Transfer count |
| 9-12 | 4 | Balance Inquiry count |
| 13-16 | 4 | Non-Cash Withdrawal count |

**Example:** `0025000300120000` = 25 CW, 3 TR, 12 BI, 0 NW

---

## 11. Configuration Request (Type 88)

### 11.1 Field Layout

| Field | Name | Format | Length | Description |
|-------|------|--------|--------|-------------|
| 0 | Information Header | AN | 9 | `H0.NNNNNN` |
| 1 | Terminal ID | AN | 6-8 | Terminal identifier |
| 2 | Transaction Code | N | 2 | `88` |
| 3 | Config Type | N | 1-2 | Configuration request type |

### 11.2 Configuration Types

| Type | Description |
|------|-------------|
| 1 | Full configuration download |
| 2 | Partial configuration update |
| 3 | Key download only |
| 4 | Surcharge update |
| 5 | Extended configuration |

---

## 12. Configuration Response (Type 88)

### 12.1 Field Layout

| Field | Name | Format | Length | Description |
|-------|------|--------|--------|-------------|
| 0 | Information Header | AN | 9 | `H0.NNNNNN` |
| 1 | Terminal ID | AN | 6-8 | Terminal identifier |
| 2 | Transaction Code | N | 2 | `88` |
| 3 | (Reserved) | - | 0 | Empty field |
| 4 | (Reserved) | - | 0 | Empty field |
| 5 | Working Key Data | H/AN | 16-80+ | Key part 1 or TR-31 block |
| 6 | Surcharge | N | 1-12 | Surcharge amount in cents |
| 7 | (Reserved) | - | 0 | Empty field |
| 8 | Working Key Part 2 | H | 16 | Key part 2 (if not TR-31) |

### 12.2 Key Format Detection

| Format | Field 5 Content | Field 8 Content |
|--------|-----------------|-----------------|
| Standard | 16 hex chars (Key Part A) | 16 hex chars (Key Part B) |
| TR-31 | Full TR-31 key block | Empty |

**Standard Key Example:**
- Field 5: `91D007980093FBD4`
- Field 8: `74D984ECCA351C41`
- Combined: `91D007980093FBD474D984ECCA351C41`

**TR-31 Key Block Example:**
- Field 5: `B0080P0TE00E000094B420079CC80BA3461F86FE26EFC4A3B8E4FA4C5F5341176EED7B727B8A248E`
- Field 8: (empty)

---

## 13. Health Check Messages

### 13.1 Health Check Request

| Field | Name | Format | Length | Description |
|-------|------|--------|--------|-------------|
| 0 | Information Header | AN | 9 | `H0.NNNNNN` |
| 1 | Terminal ID | AN | 6-8 | Terminal identifier |
| 2 | Transaction Code | N | 2 | `89` |

### 13.2 Health Check Response

| Field | Name | Format | Length | Description |
|-------|------|--------|--------|-------------|
| 0 | Information Header | AN | 9 | `H0.NNNNNN` |
| 1 | Terminal ID | AN | 6-8 | Terminal identifier |
| 2 | Transaction Code | N | 2 | `89` |
| 3 | Status | N | 2 | `00` = OK |

### 13.3 Health Check Interval

Configurable per processor (typically 6 minutes when enabled).

---

## 14. Response Codes

### 14.1 Approved Transactions

| Code | Description | Action |
|------|-------------|--------|
| 00 | Approved | Complete transaction |
| 10 | Partial Approval | Dispense approved amount |

### 14.2 Declined - Refer to Issuer

| Code | Description | Action |
|------|-------------|--------|
| 01 | Refer to Card Issuer | Display "Contact Bank" |
| 02 | Refer to Issuer (Special) | Display "Contact Bank" |

### 14.3 Declined - Card Issues

| Code | Description | Action |
|------|-------------|--------|
| 04 | Pick Up Card | Retain card |
| 05 | Do Not Honor | Decline transaction |
| 07 | Pick Up Card (Fraud) | Retain card |
| 14 | Invalid Card Number | Decline transaction |
| 33 | Expired Card - Pick Up | Retain card |
| 34 | Suspected Fraud | Retain card |
| 41 | Lost Card | Retain card |
| 43 | Stolen Card | Retain card |
| 54 | Expired Card | Decline transaction |
| 57 | Transaction Not Permitted | Decline transaction |
| 62 | Restricted Card | Decline transaction |

### 14.4 Declined - Account Issues

| Code | Description | Action |
|------|-------------|--------|
| 51 | Insufficient Funds | Decline transaction |
| 52 | No Checking Account | Decline transaction |
| 53 | No Savings Account | Decline transaction |

### 14.5 Declined - PIN/Security

| Code | Description | Action |
|------|-------------|--------|
| 55 | Incorrect PIN | Decline, allow retry |
| 75 | PIN Tries Exceeded | Retain card |
| 76 | Key Sync Error | Request new key (Type 88) |

### 14.6 Declined - Limits

| Code | Description | Action |
|------|-------------|--------|
| 61 | Exceeds Amount Limit | Decline transaction |
| 65 | Exceeds Frequency Limit | Decline transaction |

### 14.7 Declined - System/Network

| Code | Description | Action |
|------|-------------|--------|
| 12 | Invalid Transaction | Decline transaction |
| 13 | Invalid Amount | Decline transaction |
| 30 | Format Error | Decline transaction |
| 91 | Issuer Unavailable | Decline, try later |
| 96 | System Malfunction | Decline transaction |

---

## 15. Transaction Type Codes

### 15.1 Format

6-character code: `OOSSDD`

| Position | Name | Description |
|----------|------|-------------|
| 1-2 | OO | Operation Code |
| 3-4 | SS | Source Account |
| 5-6 | DD | Destination Account |

### 15.2 Operation Codes

| Code | Description |
|------|-------------|
| CW | Cash Withdrawal |
| BI | Balance Inquiry |
| TR | Transfer |
| NW | Non-Cash Withdrawal |
| DP | Deposit |

### 15.3 Account Type Codes

| Code | Description |
|------|-------------|
| CA | Checking Account |
| SA | Savings Account |
| CR | Credit Card |
| MM | Money Market |
| LN | Line of Credit |

### 15.4 Common Transaction Types

| Code | Description |
|------|-------------|
| CWCACA | Cash Withdrawal from Checking |
| CWSASA | Cash Withdrawal from Savings |
| CWCRCA | Cash Withdrawal from Credit |
| BICACA | Balance Inquiry - Checking |
| BISASA | Balance Inquiry - Savings |
| BICRCA | Balance Inquiry - Credit |
| TRCASA | Transfer Checking to Savings |
| TRSACA | Transfer Savings to Checking |
| NWCACA | Non-Cash Withdrawal - Checking |

### 15.5 Reversal Transaction Types

| Code | Description |
|------|-------------|
| CWFRRV | Full Reversal - Cash Withdrawal |
| CWPRRV | Partial Reversal - Cash Withdrawal |
| NWFRRV | Full Reversal - Non-Cash |
| NWPRRV | Partial Reversal - Non-Cash |

---

## 16. Working Key Formats

### 16.1 Standard Format (Pre-TR-31)

Working key delivered in two parts:

| Field | Content | Length |
|-------|---------|--------|
| Field 5 | First half (Key Part A) | 16 hex characters |
| Field 8 | Second half (Key Part B) | 16 hex characters |

Combined key: 32 hex characters = 16-byte double-length 3DES key

**Decryption:** Each part is decrypted separately under the Terminal Master Key (TMK).

### 16.2 TR-31 Key Block Format

For TR-31 enabled processors, Field 5 contains the complete key block:

```
VLLLLKKAMEVVXXRR[Optional Blocks]<Encrypted Key Data><MAC>
```

| Position | Length | Field | Description |
|----------|--------|-------|-------------|
| 0 | 1 | V | Version ID (A, B, C, or D) |
| 1-4 | 4 | LLLL | Total block length |
| 5-6 | 2 | KK | Key Usage |
| 7 | 1 | A | Algorithm |
| 8 | 1 | M | Mode of Use |
| 9-10 | 2 | EV | Key Version Number |
| 11 | 1 | E | Exportability |
| 12-13 | 2 | XX | Number of Optional Blocks |
| 14-15 | 2 | RR | Reserved |
| 16+ | Variable | - | Optional Blocks + Encrypted Key + MAC |

### 16.3 TR-31 Version Comparison

| Version | Binding Method | Key Derivation | MAC Size |
|---------|---------------|----------------|----------|
| A | Key Variant | XOR with 0x45 | 4 bytes |
| B | Key Derivation | CMAC-based KBEK/KBAK | 8 bytes |
| C | Key Variant | XOR with 0x45 | 4 bytes |
| D | Key Derivation | AES-CMAC | 16 bytes |

### 16.4 Key Usage Codes

| Code | Description |
|------|-------------|
| P0 | PIN Encryption Key |
| B0 | Base Derivation Key |
| D0 | Symmetric Key for Data Encryption |
| M0 | ISO 9797-1 MAC Algorithm 1 |
| M3 | ISO 9797-1 MAC Algorithm 3 |
| K0 | Key Encryption/Wrapping |

### 16.5 Algorithm Codes

| Code | Algorithm |
|------|-----------|
| T | Triple DES |
| A | AES-128 |
| B | AES-192 |
| C | AES-256 |

---

## 17. PIN Block Format

### 17.1 ISO 9564-1 Format 0

```
PIN Block = (Format + PIN + Padding) XOR (0000 + PAN12)
```

**PIN Component (8 bytes / 16 hex):**

| Position | Content |
|----------|---------|
| 0 | Format code (`0`) |
| 1 | PIN length (4-6) |
| 2-5 | PIN digits |
| 6-15 | Padding (`F`) |

**PAN Component (8 bytes / 16 hex):**

| Position | Content |
|----------|---------|
| 0-3 | Zeros (`0000`) |
| 4-15 | Rightmost 12 PAN digits (excluding check digit) |

### 17.2 Example Calculation

```
PIN: 1234 (length 4)
PAN: 4430410000008318

PIN Component: 041234FFFFFFFFFF
PAN Component: 0000304100000831

PIN Block = 041234FFFFFFFFFF XOR 0000304100000831
         = 04123EFFFFFF7CE
```

### 17.3 Encryption

- Algorithm: Triple DES (3DES) in ECB mode
- Key: Terminal Working Key (double-length, 16 bytes)
- Input: 8-byte PIN block
- Output: 8-byte encrypted PIN block (16 hex characters)

---

## 18. EMV Data Format

### 18.1 EMV Field Prefix

EMV data in Field 13 starts with identifier `ud` followed by TLV-encoded tags.

Format: `ud<TLV Data>`

### 18.2 Common EMV Tags

| Tag | Length | Description |
|-----|--------|-------------|
| 9F02 | 6 | Amount, Authorized |
| 9F03 | 6 | Amount, Other |
| 9F06 | 5-16 | Application Identifier (AID) |
| 9F09 | 2 | Application Version Number |
| 9F10 | Var | Issuer Application Data |
| 9F1A | 2 | Terminal Country Code |
| 9F1E | 8 | Interface Device Serial Number |
| 9F26 | 8 | Application Cryptogram |
| 9F27 | 1 | Cryptogram Information Data |
| 9F33 | 3 | Terminal Capabilities |
| 9F34 | 3 | Cardholder Verification Method Results |
| 9F35 | 1 | Terminal Type |
| 9F36 | 2 | Application Transaction Counter |
| 9F37 | 4 | Unpredictable Number |
| 5F2A | 2 | Transaction Currency Code |
| 5F34 | 1 | PAN Sequence Number |
| 9A | 3 | Transaction Date |
| 9C | 1 | Transaction Type |

### 18.3 EMV Response Tags

Tags returned in authorization response (Field 12):

| Tag | Description |
|-----|-------------|
| 8A | Authorization Response Code |
| 91 | Issuer Authentication Data |
| 71 | Issuer Script Template 1 |
| 72 | Issuer Script Template 2 |

---

## 19. Status Monitoring Field

### 19.1 Format

Field 12 contains status monitoring data starting with prefix `s`:

```
s<Status Indicator><Version><Device Status Data>
```

### 19.2 Common Status Fields

| Prefix | Description |
|--------|-------------|
| sX | Extended status |
| sV | Version information |
| sI | Device ID information |
| sC | Cash unit status |
| sE | Error status |

### 19.3 Example Status Data

```
sXV1.05.00I20015001C1500C2500C3000C4000E0000
```

| Component | Value | Description |
|-----------|-------|-------------|
| sX | - | Extended status marker |
| V1.05.00 | 1.05.00 | Software version |
| I20015001 | 20015001 | Device serial/ID |
| C1500 | 500 | Cassette 1: 500 notes |
| C2500 | 500 | Cassette 2: 500 notes |
| C3000 | 0 | Cassette 3: empty |
| C4000 | 0 | Cassette 4: empty |
| E0000 | 0000 | Error code (none) |

---

## 20. Handshake Protocol

### 20.1 Standard Flow

```
Terminal                    Host                    Processor
    │                        │                         │
    │──── Request ──────────>│                         │
    │                        │──── Request ───────────>│
    │                        │                         │
    │                        │<─── Response ───────────│
    │<─── Response ──────────│                         │
    │                        │                         │
    │──── ACK (0x06) ───────>│                         │
    │                        │──── ACK ───────────────>│
    │                        │                         │
    │                        │<─── EOT (0x04) ─────────│
    │<─── EOT ───────────────│                         │
    │                        │                         │
   [Session Complete]
```

### 20.2 Error Handling

If NAK (0x15) is received instead of ACK:
1. Retransmit the response up to 3 times
2. If still NAK, initiate reversal
3. Log the error condition

### 20.3 Timeout Values

| Event | Timeout | Action on Timeout |
|-------|---------|-------------------|
| Response from processor | 30-60 seconds | Reversal |
| ACK from terminal | 10 seconds | Retransmit response |
| EOT from processor | 5 seconds | Close connection |

---

## 21. Example Messages

### 21.1 Cash Withdrawal Request

**Raw (hex representation):**
```
02 48 30 2E 30 30 30 30 30 30 1C 47 48 30 30 31
30 30 33 1C 38 35 1C 43 57 43 41 43 41 1C 30 30
30 34 1C 1C 3B 34 34 33 30 34 31 30 30 30 30 30
30 38 33 31 38 3D 32 36 30 35 32 30 31 31 30 33
30 30 31 38 36 31 3F 1C 1C 38 39 45 30 32 42 44
46 32 37 35 31 45 43 41 37 1C 35 30 30 1C 31 30
30 1C 31 1C 73 58 56 31 2E 30 35 2E 30 30 2E 2E
2E 1C 75 64 39 46 30 32 2E 2E 2E 03 XX
```

**Parsed:**
```
Field 0:  H0.000000
Field 1:  GH001003
Field 2:  85
Field 3:  CWCACA
Field 4:  0004
Field 5:  (empty)
Field 6:  ;4430410000008318=2605201103001861?
Field 7:  (empty)
Field 8:  89E02BDF2751ECA7
Field 9:  500
Field 10: 100
Field 11: 1
Field 12: sXV1.05.00...
Field 13: ud9F02...
```

### 21.2 Cash Withdrawal Response (Approved)

**Parsed:**
```
Field 0:  H0.000000
Field 1:  GH001003
Field 2:  85
Field 3:  0004
Field 4:  00
Field 5:  11272025100813000000000001
Field 6:  00000100112720250
Field 7:  50000
Field 8:  50000
Field 9:  100
Field 10: (empty)
Field 11: (empty)
Field 12: ud8A023030...
```

### 21.3 Configuration Request (Key Download)

**Parsed:**
```
Field 0:  H0.000000
Field 1:  GH001003
Field 2:  88
Field 3:  3
```

### 21.4 Configuration Response (Standard Key)

**Parsed:**
```
Field 0:  H0.000000
Field 1:  GH001003
Field 2:  88
Field 3:  (empty)
Field 4:  (empty)
Field 5:  91D007980093FBD4
Field 6:  100
Field 7:  (empty)
Field 8:  74D984ECCA351C41
```

### 21.5 Configuration Response (TR-31 Key Block)

**Parsed:**
```
Field 0:  H0.000000
Field 1:  GH001003
Field 2:  88
Field 3:  (empty)
Field 4:  (empty)
Field 5:  B0080P0TE00E000094B420079CC80BA3461F86FE26EFC4A3B8E4FA4C5F5341176EED7B727B8A248E
Field 6:  100
Field 7:  (empty)
Field 8:  (empty)
```

### 21.6 Balance Inquiry Request

**Parsed:**
```
Field 0:  H0.000000
Field 1:  GH001003
Field 2:  85
Field 3:  BISASA
Field 4:  0005
Field 5:  (empty)
Field 6:  ;4430410000008318=2605201103001861?
Field 7:  (empty)
Field 8:  A3F5C8E291B6D4A0
Field 9:  0
Field 10: 0
Field 11: 0
Field 12: sXV1.05.00...
Field 13: ud9F02...
```

### 21.7 Reversal Request

**Parsed:**
```
Field 0:  H0.000000
Field 1:  GH001003
Field 2:  86
Field 3:  11272025100813000000000001
Field 4:  0004
Field 5:  ;4430410000008318=2605201103001861?
Field 6:  (empty)
Field 7:  89E02BDF2751ECA7
Field 8:  500
Field 9:  100
```

---

## 22. Processor-Specific Variations

### 22.1 Framing Types by Processor

| Processor | Framing | TLS | Port |
|-----------|---------|-----|------|
| DNS | Standard (STX/ETX) | Yes (1.2) | 8002 |
| FIS | Standard (STX/ETX) | Yes (1.2) | 443 |
| CDS | Standard (STX/ETX) | Yes (1.2) | 6965 |
| Switch Commerce | VISA (2-byte length) | Yes (1.2) | 1440 |
| Cardtronics | VISA (2-byte length) | Yes (1.2) | 5550 |
| ASAI | VISA (2-byte length) | Yes (1.2) | 30000 |
| EFX | VISA (2-byte length) | Yes (1.2) | 9057 |
| 1stISO | Standard (STX/ETX) | Yes (1.2) | 8440 |
| ITS Systems | Standard (STX/ETX) | Yes (1.2) | 777 |
| NRT/TNS | VISA (2-byte length) | Yes (1.2) | 8007 |
| Planet Payment | Standard (STX/ETX) | Yes (1.2) | 5306 |
| Worldpay | Standard (STX/ETX) | Yes (1.2) | 6661 |
| Elan/Genpass | VISA (2-byte length) | Yes (1.2) | 5166 |

### 22.2 Communication Header Variations

Some processors require an additional communication header:

| Processor | Header Required | Header Format |
|-----------|-----------------|---------------|
| DNS | No | - |
| CDS | Yes | CDSAA0 (6 chars) |
| Switch Commerce | Yes | 123SC101 (8 chars) |
| 1stISO | Yes | FSTISO (6 chars) |
| Cardtronics | Yes | CTSTRI (6 chars) |
| Worldpay | Yes | LNKATM (6 chars) |

### 22.3 Health Check Configuration

| Processor | Health Check | Interval |
|-----------|--------------|----------|
| DNS | Disabled | - |
| FIS | Enabled | 6 minutes |
| CDS | Enabled | 6 minutes |
| Switch Commerce | Disabled | - |
| Cardtronics | Disabled | - |

### 22.4 Reversal on Host Error

All processors typically enable reversal generation when host errors occur:
- `STD1_OPTION_USE_REVERSAL_HOSTERROR = Enable`

---

## Appendix A: Field Type Legend

| Code | Description |
|------|-------------|
| N | Numeric (0-9) |
| A | Alphabetic (A-Z) |
| AN | Alphanumeric |
| H | Hexadecimal (0-9, A-F) |
| B | Binary |

## Appendix B: Quick Reference - Message Type Summary

| Type | Code | Request Fields | Response Fields |
|------|------|----------------|-----------------|
| Transaction | 85 | 0-13 | 0-12 |
| Reversal | 86 | 0-10 | 0-5 |
| Host Totals | 87 | 0-3 | 0-6 |
| Configuration | 88 | 0-3 | 0-8 |
| Health Check | 89 | 0-2 | 0-3 |

## Appendix C: Revision History

| Version | Date | Description |
|---------|------|-------------|
| 1.0 | Nov 2025 | Initial specification |
| 2.0 | Nov 2025 | Added processor variations, TR-31 details, EMV tags |

---

*End of Document*
