package castech.emvtxn.atm.host;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for Hyosung STD1 Protocol implementation.
 * Tests LRC calculation, message framing, building, and parsing.
 */
public class HyosungProtocolTest {

    // =========================================================================
    // LRC Calculator Tests
    // =========================================================================

    @Test
    public void testLrcCalculation() {
        // Test basic LRC calculation
        byte[] data = new byte[] { 0x48, 0x30, 0x2E, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30 }; // "H0.000000"
        byte lrc = LrcCalculator.calculate(data);
        // XOR of all bytes
        byte expected = 0;
        for (byte b : data) {
            expected ^= b;
        }
        assertEquals(expected, lrc);
    }

    @Test
    public void testLrcWithEtx() {
        // Test LRC calculation that includes ETX
        String content = "H0.000000";
        byte lrc = LrcCalculator.calculateForMessage(content);
        // Should XOR content with ETX
        byte expected = 0;
        for (byte b : content.getBytes()) {
            expected ^= b;
        }
        expected ^= HyosungProtocol.ETX;
        assertEquals(expected, lrc);
    }

    @Test
    public void testLrcVerification() {
        byte[] data = new byte[] { 0x41, 0x42, 0x43 }; // "ABC"
        byte lrc = LrcCalculator.calculate(data);
        assertTrue(LrcCalculator.verify(data, lrc));
        assertFalse(LrcCalculator.verify(data, (byte)(lrc + 1)));
    }

    // =========================================================================
    // Message Framing Tests
    // =========================================================================

    @Test
    public void testStandardFraming() {
        String content = "H0.000000";
        byte[] framed = MessageFraming.frameStandard(content);

        // Check STX at start
        assertEquals(HyosungProtocol.STX, framed[0]);

        // Check content
        byte[] contentBytes = content.getBytes();
        for (int i = 0; i < contentBytes.length; i++) {
            assertEquals(contentBytes[i], framed[i + 1]);
        }

        // Check ETX before LRC
        assertEquals(HyosungProtocol.ETX, framed[framed.length - 2]);

        // Check total length: STX + content + ETX + LRC
        assertEquals(1 + content.length() + 1 + 1, framed.length);
    }

    @Test
    public void testVisaFraming() {
        String content = "H0.000000";
        byte[] framed = MessageFraming.frameVisa(content);

        // Calculate expected length (content + STX + ETX + LRC)
        int messageLength = 1 + content.length() + 1 + 1;

        // Check 2-byte length header (big-endian)
        assertEquals((messageLength >> 8) & 0xFF, framed[0] & 0xFF);
        assertEquals(messageLength & 0xFF, framed[1] & 0xFF);

        // Check STX after length
        assertEquals(HyosungProtocol.STX, framed[2]);

        // Check ETX before LRC
        assertEquals(HyosungProtocol.ETX, framed[framed.length - 2]);

        // Check total length: 2 + STX + content + ETX + LRC
        assertEquals(2 + 1 + content.length() + 1 + 1, framed.length);
    }

    @Test
    public void testUnframeStandard() throws MessageFraming.FramingException {
        String original = "H0.000000";
        byte[] framed = MessageFraming.frameStandard(original);
        byte[] content = MessageFraming.unframeStandard(framed);

        assertEquals(original, new String(content));
    }

    @Test
    public void testUnframeVisa() throws MessageFraming.FramingException {
        String original = "H0.000000";
        byte[] framed = MessageFraming.frameVisa(original);
        byte[] content = MessageFraming.unframeVisa(framed);

        assertEquals(original, new String(content));
    }

    @Test(expected = MessageFraming.FramingException.class)
    public void testUnframeInvalidLrc() throws MessageFraming.FramingException {
        String content = "H0.000000";
        byte[] framed = MessageFraming.frameStandard(content);
        // Corrupt the LRC
        framed[framed.length - 1] = (byte)(framed[framed.length - 1] + 1);
        MessageFraming.unframeStandard(framed);
    }

    @Test
    public void testFieldSplitting() {
        // Build fields with FS separator
        String[] original = new String[] { "Field1", "Field2", "Field3" };
        byte[] joined = MessageFraming.joinFields(original);

        // Split back
        String[] split = MessageFraming.splitFields(joined);

        assertArrayEquals(original, split);
    }

    // =========================================================================
    // Message Builder Tests
    // =========================================================================

    @Test
    public void testBuildTransactionRequest() {
        HyosungMessageBuilder builder = new HyosungMessageBuilder();

        TransactionRequest request = TransactionRequest.createCashWithdrawal(
            "GH001003",
            ";4430410000008318=26052011030018610000?",
            "89E02BDF2751ECA7",
            50000,  // $500.00
            100,    // $1.00 surcharge
            HyosungProtocol.ACCT_CHECKING
        );
        request.setSequenceNumber(4);
        request.setStatusMonitoring("sXV1.05.00");
        request.setEmvData("ud9F02000000005000");

        byte[] message = builder.buildTransactionRequest(request);

        // Verify message is not null and has minimum length
        assertNotNull(message);
        assertTrue(message.length > 10);

        // Verify STX at start
        assertEquals(HyosungProtocol.STX, message[0]);

        // Verify ETX near end
        assertEquals(HyosungProtocol.ETX, message[message.length - 2]);
    }

    @Test
    public void testBuildConfigRequest() {
        HyosungMessageBuilder builder = new HyosungMessageBuilder();

        ConfigRequest request = ConfigRequest.createKeyDownload("GH001003");
        byte[] message = builder.buildConfigRequest(request);

        assertNotNull(message);
        assertTrue(message.length > 5);
    }

    @Test
    public void testBuildHealthCheckRequest() {
        HyosungMessageBuilder builder = new HyosungMessageBuilder();

        HealthCheckRequest request = HealthCheckRequest.create("GH001003");
        byte[] message = builder.buildHealthCheckRequest(request);

        assertNotNull(message);
        assertTrue(message.length > 5);
    }

    @Test
    public void testBuildReversalRequest() {
        HyosungMessageBuilder builder = new HyosungMessageBuilder();

        // Create original transaction
        TransactionRequest originalRequest = TransactionRequest.createCashWithdrawal(
            "GH001003",
            ";4430410000008318=26052011030018610000?",
            "89E02BDF2751ECA7",
            50000, 100,
            HyosungProtocol.ACCT_CHECKING
        );
        originalRequest.setSequenceNumber(4);

        // Create original response
        TransactionResponse originalResponse = new TransactionResponse();
        originalResponse.setAuthorizationData("11272025100813000000000001");

        // Create reversal
        ReversalRequest reversal = ReversalRequest.createDispenseFailureReversal(
            originalRequest, originalResponse);

        byte[] message = builder.buildReversalRequest(reversal);

        assertNotNull(message);
        assertTrue(message.length > 10);
    }

    @Test
    public void testAckNakEotMessages() {
        HyosungMessageBuilder builder = new HyosungMessageBuilder();

        byte[] ack = builder.buildAck();
        assertEquals(1, ack.length);
        assertEquals(HyosungProtocol.ACK, ack[0]);

        byte[] nak = builder.buildNak();
        assertEquals(1, nak.length);
        assertEquals(HyosungProtocol.NAK, nak[0]);

        byte[] eot = builder.buildEot();
        assertEquals(1, eot.length);
        assertEquals(HyosungProtocol.EOT, eot[0]);
    }

    // =========================================================================
    // Message Parser Tests
    // =========================================================================

    @Test
    public void testParseTransactionResponse() throws Exception {
        HyosungMessageBuilder builder = new HyosungMessageBuilder();
        HyosungMessageParser parser = new HyosungMessageParser();

        // Build a mock response
        String[] fields = new String[] {
            "H0.000000",       // Field 0: Info Header
            "GH001003",        // Field 1: Terminal ID
            "85",              // Field 2: Transaction Code
            "0004",            // Field 3: Sequence Number
            "00",              // Field 4: Response Code (Approved)
            "11272025100813000000000001", // Field 5: Auth Data
            "00000100112720250", // Field 6: Settlement Data
            "50000",           // Field 7: Account Balance
            "50000",           // Field 8: Available Balance
            "100",             // Field 9: Surcharge
            "",                // Field 10: Display Message
            "",                // Field 11: Config Indicator
            ""                 // Field 12: EMV Response
        };
        byte[] content = MessageFraming.joinFields(fields);
        byte[] framed = MessageFraming.frameStandard(content);

        // Parse
        TransactionResponse response = parser.parseTransactionResponse(framed);

        assertEquals("GH001003", response.getTerminalId());
        assertEquals(4, response.getSequenceNumber());
        assertEquals("00", response.getResponseCode());
        assertTrue(response.isApproved());
        assertEquals(50000, response.getAccountBalanceCents());
        assertEquals(50000, response.getAvailableBalanceCents());
        assertEquals(100, response.getSurchargeCents());
        assertEquals("000000000001", response.getRetrievalReferenceNumber());
    }

    @Test
    public void testParseConfigResponse() throws Exception {
        HyosungMessageParser parser = new HyosungMessageParser();

        // Build a mock config response with standard key format
        String[] fields = new String[] {
            "H0.000000",       // Field 0
            "GH001003",        // Field 1
            "88",              // Field 2
            "",                // Field 3: Reserved
            "",                // Field 4: Reserved
            "91D007980093FBD4", // Field 5: Key Part 1
            "100",             // Field 6: Surcharge
            "",                // Field 7: Reserved
            "74D984ECCA351C41"  // Field 8: Key Part 2
        };
        byte[] content = MessageFraming.joinFields(fields);
        byte[] framed = MessageFraming.frameStandard(content);

        // Parse
        ConfigResponse response = parser.parseConfigResponse(framed);

        assertEquals("GH001003", response.getTerminalId());
        assertFalse(response.isTr31Format());
        assertEquals("91D007980093FBD474D984ECCA351C41", response.getCombinedWorkingKey());
        assertEquals(100, response.getSurchargeCents());
    }

    @Test
    public void testParseConfigResponseTr31() throws Exception {
        HyosungMessageParser parser = new HyosungMessageParser();

        // Build a mock config response with TR-31 key block
        String tr31Block = "B0080P0TE00E000094B420079CC80BA3461F86FE26EFC4A3B8E4FA4C5F5341176EED7B727B8A248E";
        String[] fields = new String[] {
            "H0.000000",       // Field 0
            "GH001003",        // Field 1
            "88",              // Field 2
            "",                // Field 3
            "",                // Field 4
            tr31Block,         // Field 5: TR-31 block
            "100",             // Field 6: Surcharge
            "",                // Field 7
            ""                 // Field 8: Empty for TR-31
        };
        byte[] content = MessageFraming.joinFields(fields);
        byte[] framed = MessageFraming.frameStandard(content);

        // Parse
        ConfigResponse response = parser.parseConfigResponse(framed);

        assertTrue(response.isTr31Format());
        assertEquals(tr31Block, response.getTr31KeyBlock());
        assertEquals("B", response.getTr31Version());
        assertEquals("P0", response.getTr31KeyUsage());
        assertEquals("T", response.getTr31Algorithm());
        assertTrue(response.isPinEncryptionKey());
        assertTrue(response.is3DesKey());
    }

    @Test
    public void testParseHealthCheckResponse() throws Exception {
        HyosungMessageParser parser = new HyosungMessageParser();

        String[] fields = new String[] {
            "H0.000000",
            "GH001003",
            "89",
            "00"
        };
        byte[] content = MessageFraming.joinFields(fields);
        byte[] framed = MessageFraming.frameStandard(content);

        HealthCheckResponse response = parser.parseHealthCheckResponse(framed);

        assertEquals("GH001003", response.getTerminalId());
        assertEquals("00", response.getStatus());
        assertTrue(response.isOk());
    }

    @Test
    public void testControlMessageDetection() {
        HyosungMessageParser parser = new HyosungMessageParser();

        assertTrue(parser.isAck(new byte[] { HyosungProtocol.ACK }));
        assertTrue(parser.isNak(new byte[] { HyosungProtocol.NAK }));
        assertTrue(parser.isEot(new byte[] { HyosungProtocol.EOT }));

        assertFalse(parser.isAck(new byte[] { HyosungProtocol.NAK }));
        assertFalse(parser.isControlMessage(new byte[] { 0x41, 0x42 }));
    }

    // =========================================================================
    // Round-Trip Tests
    // =========================================================================

    @Test
    public void testRoundTripTransactionMessage() throws Exception {
        HyosungMessageBuilder builder = new HyosungMessageBuilder();
        HyosungMessageParser parser = new HyosungMessageParser();

        // Build request
        TransactionRequest request = TransactionRequest.createCashWithdrawal(
            "GH001003",
            ";4430410000008318=26052011030018610000?",
            "89E02BDF2751ECA7",
            50000, 100,
            HyosungProtocol.ACCT_CHECKING
        );
        request.setSequenceNumber(4);

        byte[] message = builder.buildTransactionRequest(request);

        // Verify we can extract fields
        String[] fields = parser.parseFields(message);

        assertEquals("H0.000000", fields[0]);
        assertEquals("GH001003", fields[1]);
        assertEquals("85", fields[2]);
        assertEquals("CWCACA", fields[3]);
        assertEquals("0004", fields[4]);
    }

    @Test
    public void testRoundTripWithVisaFraming() throws Exception {
        HyosungMessageBuilder builder = new HyosungMessageBuilder(HyosungProtocol.FramingType.VISA_LENGTH_PREFIX);
        HyosungMessageParser parser = new HyosungMessageParser(HyosungProtocol.FramingType.VISA_LENGTH_PREFIX);

        TransactionRequest request = TransactionRequest.createBalanceInquiry(
            "GH001003",
            ";4430410000008318=26052011030018610000?",
            "A3F5C8E291B6D4A0",
            HyosungProtocol.ACCT_SAVINGS
        );
        request.setSequenceNumber(5);

        byte[] message = builder.buildTransactionRequest(request);
        String[] fields = parser.parseFields(message);

        assertEquals("H0.000000", fields[0]);
        assertEquals("GH001003", fields[1]);
        assertEquals("85", fields[2]);
        assertEquals("BISASA", fields[3]);
        assertEquals("0005", fields[4]);
    }

    // =========================================================================
    // Protocol Helper Tests
    // =========================================================================

    @Test
    public void testResponseCodeHelpers() {
        assertTrue(HyosungProtocol.isApproved("00"));
        assertTrue(HyosungProtocol.isApproved("10")); // Partial approval
        assertFalse(HyosungProtocol.isApproved("51")); // Insufficient funds

        assertTrue(HyosungProtocol.shouldRetainCard("04")); // Pick up card
        assertTrue(HyosungProtocol.shouldRetainCard("41")); // Lost card
        assertTrue(HyosungProtocol.shouldRetainCard("43")); // Stolen card
        assertFalse(HyosungProtocol.shouldRetainCard("51")); // Insufficient funds

        assertTrue(HyosungProtocol.requiresKeySync("76")); // Key sync error
        assertFalse(HyosungProtocol.requiresKeySync("00"));
    }

    @Test
    public void testBuildInfoHeader() {
        assertEquals("H0.000000", HyosungProtocol.buildInfoHeader("000000"));
        assertEquals("H0.SC101", HyosungProtocol.buildInfoHeader("SC101"));
        // Invalid input should use default
        assertEquals("H0.000000", HyosungProtocol.buildInfoHeader(null));
        assertEquals("H0.000000", HyosungProtocol.buildInfoHeader("123")); // Too short
    }

    @Test
    public void testBuildTransactionType() {
        assertEquals("CWCACA", HyosungProtocol.buildTransactionType("CW", "CA", "CA"));
        assertEquals("BISASA", HyosungProtocol.buildTransactionType("BI", "SA", "SA"));
        assertEquals("TRCASA", HyosungProtocol.buildTransactionType("TR", "CA", "SA"));
    }

    @Test
    public void testResponseDescription() {
        assertEquals("Approved", HyosungProtocol.getResponseDescription("00"));
        assertEquals("Insufficient Funds", HyosungProtocol.getResponseDescription("51"));
        assertEquals("Incorrect PIN", HyosungProtocol.getResponseDescription("55"));
        assertTrue(HyosungProtocol.getResponseDescription("99").contains("Unknown"));
    }

    // =========================================================================
    // Processor Config Tests
    // =========================================================================

    @Test
    public void testProcessorConfigDns() {
        ProcessorConfig config = ProcessorConfig.forDns("dns.example.com", "GH001003");

        assertEquals("DNS", config.getName());
        assertEquals("dns.example.com", config.getHost());
        assertEquals(8002, config.getPort());
        assertEquals(HyosungProtocol.FramingType.STANDARD, config.getFramingType());
        assertFalse(config.isHealthCheckEnabled());
    }

    @Test
    public void testProcessorConfigSwitchCommerce() {
        ProcessorConfig config = ProcessorConfig.forSwitchCommerce("sc.example.com", "GH001003");

        assertEquals("Switch Commerce", config.getName());
        assertEquals(1440, config.getPort());
        // Switch Commerce uses VISA framing WITHOUT STX/ETX/LRC
        assertEquals(HyosungProtocol.FramingType.VISA_NO_STX_ETX, config.getFramingType());
        assertEquals("SC101", config.getRoutingId());
        assertEquals("123SC101", config.getCommunicationHeader());
    }

    @Test
    public void testProcessorConfigCreateBuilderAndParser() {
        // EFX uses VISA framing WITHOUT STX/ETX/LRC
        ProcessorConfig config = ProcessorConfig.forEfx("efx.example.com", "GH001003");

        HyosungMessageBuilder builder = config.createMessageBuilder();
        HyosungMessageParser parser = config.createMessageParser();

        assertEquals(HyosungProtocol.FramingType.VISA_NO_STX_ETX, builder.getFramingType());
        assertEquals(HyosungProtocol.FramingType.VISA_NO_STX_ETX, parser.getFramingType());
    }

    // =========================================================================
    // Debug Output Tests
    // =========================================================================

    @Test
    public void testToHexString() {
        byte[] data = new byte[] { 0x02, 0x48, 0x30, 0x03 };
        String hex = HyosungMessageBuilder.toHexString(data);
        assertEquals("02 48 30 03", hex);
    }

    @Test
    public void testToReadableString() {
        byte[] data = new byte[] { HyosungProtocol.STX, 0x48, 0x30, HyosungProtocol.FS, 0x41, HyosungProtocol.ETX };
        String readable = HyosungMessageBuilder.toReadableString(data);
        assertEquals("<STX>H0<FS>A<ETX>", readable);
    }
}
