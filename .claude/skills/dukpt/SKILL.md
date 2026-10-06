---
name: dukpt
description: |
  Manages DUKPT key derivation, PIN encryption, and key injection procedures for Castle S1F4 PRO terminals.
  Use when: implementing PIN encryption, injecting keys via Key Injection Tool, troubleshooting key attribute errors (0x1003), or working with CtKMS2 SDK
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

# DUKPT Skill

DUKPT (Derived Unique Key Per Transaction) implementation for Castle S1F4 PRO payment terminals. Handles key injection, PIN block encryption, and working key management through Castle's CtKMS2 SDK.

**Critical Issue**: Key Injection Tool v2.01 sets attribute 0x00000010 (DECRYPT) but SDK internal PIN flow requires 0x00000001 (PIN). Manual DUKPT via `CtKMS2Dukpt` works; SDK internal PIN fails with 0x1003.

## Quick Reference

### Key Locations

| Location | Purpose | Attribute Required |
|----------|---------|-------------------|
| C000/0000 | DUKPT IPEK (injected) | PIN (0x01) - but tool sets DECRYPT (0x10) |
| C001/001A | DUKPT(1A) slot | PIN (0x01) |
| CFFF/0000 | TMK/KBPK | KBPK (0x20) - tool cannot set |

### PIN Encryption (Working Approach)

```java
// Manual DUKPT PIN encryption - WORKS at C000/0000
CtKMS2Dukpt dukpt = new CtKMS2Dukpt();
dukpt.selectKey(0xC000, 0x0000);
dukpt.setCipherMethod(CtKMS2Dukpt.DATA_ENCRYPT_METHOD_ECB);
dukpt.setInputData(clearPinBlock, 0, clearPinBlock.length);
dukpt.isUseCurrentKey(false);  // Increment KSN
dukpt.dataEncrypt();

byte[] encrypted = dukpt.getOutpuData();
byte[] ksn = dukpt.getKSN();
```

### Format 0 PIN Block

```java
// PIN: 1234, PAN: 4012345678909
// PIN component: 0 | length | PIN | F-padding = 041234FFFFFFFFFF
// PAN component: 0000 | rightmost 12 (no check) = 0000401234567890
// XOR them together for clear PIN block

String clearBlock = PinBlockFormatter.createFormat0PinBlock("1234", "4012345678909");
```

## Key Injection Workflow

```
1. Factory Reset → SystemPanel → 00000000
2. KEK Injection → Key_Injection app → C000/0000
3. Generate TR31 → Key Injection Tool → IPEK + KSN
4. DUKPT Injection → Key_Bridge app → Geobridge SW
5. Load Config/App → Loader tool + ADB
```

## Common Errors

| Error | Cause | Fix |
|-------|-------|-----|
| 0x1003 | SDK internal PIN attribute mismatch | Use manual `CtKMS2Dukpt` instead |
| 0x2901 | Key not found | Verify key location matches injection |
| 0x2907 | Wrong key attribute for operation | Use software fallback decryption |
| b004 | TR31 unwrap failed | KEK mismatch - re-inject with same KEK |

## See Also

- [patterns](references/patterns.md) - PIN encryption and key management patterns
- [workflows](references/workflows.md) - Key injection and troubleshooting workflows

## Related Skills

- See the **emv** skill for EMV tag handling and cryptogram generation
- See the **java** skill for threading patterns (all KMS2 operations on background thread)
- See the **castle-sdk** skill for SDK initialization