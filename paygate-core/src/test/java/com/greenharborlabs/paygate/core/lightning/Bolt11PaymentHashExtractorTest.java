package com.greenharborlabs.paygate.core.lightning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link Bolt11PaymentHashExtractor}.
 *
 * <p>Specification vectors are copied verbatim from the "Examples" and "Examples of Invalid
 * Invoices" sections of BOLT #11 ({@code 11-payment-encoding.md}, lightning/bolts master at commit
 * {@code 191462825ae341f0a27098765c075f01ca9bc6c2}). Cases the specification does not cover, such
 * as a {@code p} field of the wrong length, are built with the test-side bech32 encoder below.
 */
class Bolt11PaymentHashExtractorTest {

  private static final HexFormat HEX = HexFormat.of();

  /** Payment hash used by every BOLT #11 example except the pico-BTC one. */
  private static final String SPEC_PAYMENT_HASH =
      "0001020304050607080900010203040506070809000102030405060708090102";

  @Nested
  @DisplayName("BOLT #11 valid examples")
  class SpecificationVectors {

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
        strings = {
          // Please make a donation of any amount using payment_hash 0001...0102 to me @03e7...
          "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql",
          // Please send $3 for a cup of coffee to the same peer, within one minute
          "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh",
          // Please send 0.0025 BTC for a cup of nonsense to the same peer, within one minute
          "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpu9qrsgqhtjpauu9ur7fw2thcl4y9vfvh4m9wlfyz2gem29g5ghe2aak2pm3ps8fdhtceqsaagty2vph7utlgj48u0ged6a337aewvraedendscp573dxr",
          // Now send $24 for an entire list of things (hashed)
          "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqs9qrsgq7ea976txfraylvgzuxs8kgcw23ezlrszfnh8r6qtfpr6cxga50aj6txm9rxrydzd06dfeawfk6swupvz4erwnyutnjq7x39ymw6j38gp7ynn44",
          // The same, on testnet, with a fallback address
          "lntb20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfpp3x9et2e20v6pu37c5d9vax37wxq72un989qrsgqdj545axuxtnfemtpwkc45hx9d2ft7x04mt8q7y6t0k2dge9e7h8kpy9p34ytyslj3yu569aalz2xdk8xkd7ltxqld94u8h2esmsmacgpghe9k8",
          // On mainnet, with fallback address and extra routing info
          "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3qjmp7lwpagxun9pygexvgpjdc4jdj85fr9yq20q82gphp2nflc7jtzrcazrra7wwgzxqc8u7754cdlpfrmccae92qgzqvzq2ps8pqqqqqqpqqqqq9qqqvpeuqafqxu92d8lr6fvg0r5gv0heeeqgcrqlnm6jhphu9y00rrhy4grqszsvpcgpy9qqqqqqgqqqqq7qqzq9qrsgqdfjcdk6w3ak5pca9hwfwfh63zrrz06wwfya0ydlzpgzxkn5xagsqz7x9j4jwe7yj7vaf2k9lqsdk45kts2fd0fkr28am0u4w95tt2nsq76cqw0",
          // On mainnet, with fallback (P2SH) address
          "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfppj3a24vwu6r8ejrss3axul8rxldph2q7z99qrsgqz6qsgww34xlatfj6e3sngrwfy3ytkt29d2qttr8qz2mnedfqysuqypgqex4haa2h8fx3wnypranf3pdwyluftwe680jjcfp438u82xqphf75ym",
          // On mainnet, with fallback (P2WPKH) address
          "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfppqw508d6qejxtdg4y5r3zarvary0c5xw7k9qrsgqt29a0wturnys2hhxpner2e3plp6jyj8qx7548zr2z7ptgjjc7hljm98xhjym0dg52sdrvqamxdezkmqg4gdrvwwnf0kv2jdfnl4xatsqmrnsse",
          // On mainnet, with fallback (P2WSH) address
          "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q9qrsgq9vlvyj8cqvq6ggvpwd53jncp9nwc47xlrsnenq2zp70fq83qlgesn4u3uyf4tesfkkwwfg3qs54qe426hp3tz7z6sweqdjg05axsrjqp9yrrwc",
          // On mainnet, with fallback (P2TR) address
          "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfp4pptdvg0d2nj99568qn6ssdy4cygnwuxgw2ukmnwgwz7jpqjz2kszs9qrsgqy606dznq28exnydt2r4c29y56xjtn3sk4mhgjtl4pg2y4ar3249rq4ajlmj9jy8zvlzw7cr8mggqzm842xfr0v72rswzq9xvr4hknfsqwmn6xd",
          // Please send $30 for coffee beans to the same peer, which supports features 8, 14 and 99
          "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqqsgq2a25dxl5hrntdtn6zvydt7d66hyzsyhqs4wdynavys42xgl6sgx9c4g7me86a27t07mdtfry458rtjr0v92cnmswpsjscgt2vcse3sgpz3uapa",
          // Same, but including fields which must be ignored
          "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqqsgq2qrqqqfppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqcsenjd934c02h7v9deyju0t43ye2y38nd2h2wr0ykzlqs7v873jkvvg36kfwwmp9lftrn8a3gp4ax52zpvyc7quffgjjj7avx7we9zcp3dce9d",
          // Please send 0.01 BTC with payment metadata 0x01fafaf0
          "lnbc10m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdp9wpshjmt9de6zqmt9w3skgct5vysxjmnnd9jx2mq8q8a04uqsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q2gqqqqqqsgq7hf8he7ecf7n4ffphs6awl9t6676rrclv9ckg3d3ncn7fct63p6s365duk5wrk202cfy3aj5xnnp5gs3vrdvruverwwq7yzhkf5a3xqpd05wjc",
          // Public-key recovery with high-S signature
          "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap2r09nt4ndd0unm3z9u5t48y6ucv4r5sg7lk98c77ctvjczkspk5qprc90gx"
        })
    void extractsSpecificationPaymentHash(String invoice) {
      assertThat(HEX.formatHex(Bolt11PaymentHashExtractor.extract(invoice)))
          .isEqualTo(SPEC_PAYMENT_HASH);
    }

    @Test
    @DisplayName("pico-BTC example yields its own payment hash")
    void extractsPicoBtcExamplePaymentHash() {
      String invoice =
          "lnbc9678785340p1pwmna7lpp5gc3xfm08u9qy06djf8dfflhugl6p7lgza6dsjxq454gxhj9t7a0sd8dgfkx7cmtwd68yetpd5s9xar0wfjn5gpc8qhrsdfq24f5ggrxdaezqsnvda3kkum5wfjkzmfqf3jkgem9wgsyuctwdus9xgrcyqcjcgpzgfskx6eqf9hzqnteypzxz7fzypfhg6trddjhygrcyqezcgpzfysywmm5ypxxjemgw3hxjmn8yptk7untd9hxwg3q2d6xjcmtv4ezq7pqxgsxzmnyyqcjqmt0wfjjq6t5v4khxsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygsxqyjw5qcqp2rzjq0gxwkzc8w6323m55m4jyxcjwmy7stt9hwkwe2qxmy8zpsgg7jcuwz87fcqqeuqqqyqqqqlgqqqqn3qq9q9qrsgqrvgkpnmps664wgkp43l22qsgdw4ve24aca4nymnxddlnp8vh9v2sdxlu5ywdxefsfvm0fq3sesf08uf6q9a2ke0hc9j6z6wlxg5z5kqpu2v9wz";

      assertThat(HEX.formatHex(Bolt11PaymentHashExtractor.extract(invoice)))
          .isEqualTo("462264ede7e14047e9b249da94fefc47f41f7d02ee9b091815a5506bc8abf75f");
    }

    @Test
    @DisplayName("each call returns a fresh array")
    void returnsFreshArray() {
      String invoice =
          "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqqsgq2a25dxl5hrntdtn6zvydt7d66hyzsyhqs4wdynavys42xgl6sgx9c4g7me86a27t07mdtfry458rtjr0v92cnmswpsjscgt2vcse3sgpz3uapa";

      byte[] first = Bolt11PaymentHashExtractor.extract(invoice);
      first[0] = (byte) 0xff;

      assertThat(HEX.formatHex(Bolt11PaymentHashExtractor.extract(invoice)))
          .isEqualTo(SPEC_PAYMENT_HASH);
    }
  }

  @Nested
  @DisplayName("BOLT #11 invalid examples")
  class SpecificationRejections {

    @Test
    @DisplayName("missing required p field")
    void rejectsMissingPaymentHash() {
      assertRejected(
          "lnbc25m1pvjluezdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqqsgqjy08jd52dtj25pefkh0enc54lxtjtr8wnum2msrq2d8uztp9vhhpccq93ljpff0kkjgzkzcp2czlyenjphf6upazjlc8uxw7za0ynusqrfhg8u",
          "BOLT11 invoice has no payment hash");
    }

    @Test
    @DisplayName("two distinct p fields")
    void rejectsTwoDistinctPaymentHashes() {
      assertRejected(
          "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqpp5lllllllllllllllllllllllllllllllllllllllllllllllllllsdpyv36hqmrfvdshgefqwpshjmt9de6zq6rpwd5qsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9g3f93cqturay6zk2fyfcmeflphlzew9wfq0n5nf9hqnlwxthtzqcljcuurljyd2vngkya5hndakf33ghly97qm5nc3umj7jep22nfsq3nr0w8",
          "BOLT11 invoice has more than one payment hash");
    }

    @Test
    @DisplayName("the same p field twice")
    void rejectsRepeatedPaymentHash() {
      assertRejected(
          "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpyv36hqmrfvdshgefqwpshjmt9de6zq6rpwd5qsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs29wywgsx0wpv9t045f683nj97nnjk55wt0exe3eassl6smx60nk9hlaae8vhe0hwv25s6fthcwqkxsw2hpjeptxz7xujtexa3l8jrkcqyn037r",
          "BOLT11 invoice has more than one payment hash");
    }

    @Test
    @DisplayName("bech32 checksum is invalid")
    void rejectsInvalidChecksum() {
      assertRejected(
          "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsrnt",
          "BOLT11 invoice checksum is invalid");
    }

    @Test
    @DisplayName("malformed bech32 string with no separator")
    void rejectsMissingSeparator() {
      assertRejected(
          "pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsrny",
          "BOLT11 invoice has an invalid human-readable part");
    }

    @Test
    @DisplayName("malformed bech32 string with mixed case")
    void rejectsMixedCase() {
      assertRejected(
          "LNBC2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsrny",
          "BOLT11 invoice has an invalid human-readable part");
    }

    @Test
    @DisplayName("string is too short")
    void rejectsTooShort() {
      assertRejected(
          "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6na6hlh",
          "BOLT11 invoice data part is too short");
    }

    @Test
    @DisplayName(
        "all-uppercase example is rejected: Paygate accepts only lowercase backend invoices")
    void rejectsAllUppercaseSpecificationExample() {
      // BOLT #11 lists this as valid; Paygate deliberately accepts lowercase only.
      assertRejected(
          "LNBC25M1PVJLUEZPP5QQQSYQCYQ5RQWZQFQQQSYQCYQ5RQWZQFQQQSYQCYQ5RQWZQFQYPQDQ5VDHKVEN9V5SXYETPDEESSP5ZYG3ZYG3ZYG3ZYG3ZYG3ZYG3ZYG3ZYG3ZYG3ZYG3ZYG3ZYG3ZYGS9Q5SQQQQQQQQQQQQQQQQSGQ2A25DXL5HRNTDTN6ZVYDT7D66HYZSYHQS4WDYNAVYS42XGL6SGX9C4G7ME86A27T07MDTFRY458RTJR0V92CNMSWPSJSCGT2VCSE3SGPZ3UAPA",
          "BOLT11 invoice has an invalid human-readable part");
    }
  }

  @Nested
  @DisplayName("constructed invoices")
  class ConstructedInvoices {

    private final byte[] hash = HEX.parseHex(SPEC_PAYMENT_HASH);
    private final byte[] otherHash =
        HEX.parseHex("ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100");

    @Test
    @DisplayName("encoder round-trips a minimal invoice")
    void extractsFromMinimalConstructedInvoice() {
      String invoice = new InvoiceBuilder("lnbc").paymentHash(hash).build();

      assertThat(Bolt11PaymentHashExtractor.extract(invoice)).isEqualTo(hash);
    }

    @Test
    @DisplayName("a p field of the wrong length is skipped in favour of the valid one")
    void skipsPaymentHashFieldOfWrongLength() {
      String invoice =
          new InvoiceBuilder("lnbc")
              .field(1, wordsOf(otherHash, 51))
              .paymentHash(hash)
              .field(1, wordsOf(otherHash, 53))
              .build();

      assertThat(Bolt11PaymentHashExtractor.extract(invoice)).isEqualTo(hash);
    }

    @Test
    @DisplayName("an invoice whose only p fields have the wrong length has no payment hash")
    void rejectsWhenOnlyWrongLengthPaymentHashFields() {
      String invoice =
          new InvoiceBuilder("lnbc")
              .field(1, wordsOf(otherHash, 51))
              .field(1, wordsOf(otherHash, 53))
              .build();

      assertRejected(invoice, "BOLT11 invoice has no payment hash");
    }

    @Test
    @DisplayName("two valid p fields are rejected")
    void rejectsTwoValidPaymentHashFields() {
      String invoice = new InvoiceBuilder("lnbc").paymentHash(hash).paymentHash(otherHash).build();

      assertRejected(invoice, "BOLT11 invoice has more than one payment hash");
    }

    @Test
    @DisplayName("a p field with non-zero padding bits is rejected")
    void rejectsNonCanonicalPadding() {
      int[] words = wordsOf(hash, 52);
      words[51] |= 0x1;
      String invoice = new InvoiceBuilder("lnbc").field(1, words).build();

      assertRejected(invoice, "BOLT11 invoice payment hash has non-zero padding");
    }

    @Test
    @DisplayName("a wrong human-readable prefix is rejected even with a valid checksum")
    void rejectsWrongPrefix() {
      String invoice = new InvoiceBuilder("bc").paymentHash(hash).build();

      assertRejected(invoice, "BOLT11 invoice has an invalid human-readable part");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ln", "lnb-", "lnbc_"})
    @DisplayName("a human-readable part without a currency or with symbols is rejected")
    void rejectsInvalidHumanReadablePart(String hrp) {
      String invoice = new InvoiceBuilder(hrp).paymentHash(hash).build();

      assertRejected(invoice, "BOLT11 invoice has an invalid human-readable part");
    }

    @Test
    @DisplayName("a tagged field whose length runs into the signature is rejected")
    void rejectsTaggedFieldOverrunningSignature() {
      // Declares 60 words of data but only 52 follow before the signature.
      String invoice =
          new InvoiceBuilder("lnbc").rawWords(1, 1, 28).rawWords(wordsOf(hash, 52)).build();

      assertRejected(invoice, "BOLT11 invoice has a truncated tagged field");
    }

    @Test
    @DisplayName("a tagged-field header cut short by the signature is rejected")
    void rejectsTruncatedTaggedFieldHeader() {
      String invoice = new InvoiceBuilder("lnbc").paymentHash(hash).rawWords(13, 0).build();

      assertRejected(invoice, "BOLT11 invoice has a truncated tagged field");
    }

    @Test
    @DisplayName("a data part one word short of timestamp, signature and checksum is rejected")
    void rejectsDataPartBelowMinimum() {
      String invoice = new InvoiceBuilder("lnbc").signatureWords(103).build();

      assertRejected(invoice, "BOLT11 invoice data part is too short");
    }

    @Test
    @DisplayName("truncating a valid invoice breaks the checksum")
    void rejectsTruncatedValidInvoice() {
      String invoice = new InvoiceBuilder("lnbc").paymentHash(hash).build();

      assertRejected(
          invoice.substring(0, invoice.length() - 1), "BOLT11 invoice checksum is invalid");
    }

    @Test
    @DisplayName("a single flipped data character breaks the checksum")
    void rejectsCorruptedCharacter() {
      String invoice = new InvoiceBuilder("lnbc").paymentHash(hash).build();
      int index = invoice.indexOf('1') + 20;
      char replacement = invoice.charAt(index) == 'q' ? 'p' : 'q';
      String corrupted = invoice.substring(0, index) + replacement + invoice.substring(index + 1);

      assertRejected(corrupted, "BOLT11 invoice checksum is invalid");
    }

    @Test
    @DisplayName("an uppercase character in the data part is rejected")
    void rejectsUppercaseDataCharacter() {
      String invoice = new InvoiceBuilder("lnbc").paymentHash(hash).build();
      int index = invoice.length() - 1;
      while (!Character.isLetter(invoice.charAt(index))) {
        index--;
      }
      String mixed =
          invoice.substring(0, index)
              + Character.toUpperCase(invoice.charAt(index))
              + invoice.substring(index + 1);

      assertRejected(mixed, "BOLT11 invoice contains an invalid character");
    }

    @ParameterizedTest
    @ValueSource(strings = {"b", "i", "o", "é"})
    @DisplayName("characters outside the bech32 alphabet are rejected")
    void rejectsCharactersOutsideAlphabet(String character) {
      String invoice = new InvoiceBuilder("lnbc").paymentHash(hash).build();
      int index = invoice.indexOf('1') + 10;
      String bad = invoice.substring(0, index) + character + invoice.substring(index + 1);

      assertRejected(bad, "BOLT11 invoice contains an invalid character");
    }

    @Test
    @DisplayName("an invoice at the length limit is accepted and one character over is rejected")
    void enforcesMaximumLength() {
      int fixedLength = new InvoiceBuilder("lnbc").paymentHash(hash).build().length();
      int padding = Bolt11PaymentHashExtractor.MAX_INVOICE_LENGTH - fixedLength;
      // Amount digits in the human-readable part are not interpreted, so they pad the length.
      String atLimit = new InvoiceBuilder("lnbc" + "0".repeat(padding)).paymentHash(hash).build();
      String overLimit =
          new InvoiceBuilder("lnbc" + "0".repeat(padding + 1)).paymentHash(hash).build();

      assertThat(atLimit).hasSize(Bolt11PaymentHashExtractor.MAX_INVOICE_LENGTH);
      assertThat(Bolt11PaymentHashExtractor.extract(atLimit)).isEqualTo(hash);
      assertRejected(overLimit, "BOLT11 invoice exceeds the maximum length");
    }

    @Test
    @DisplayName("null is rejected")
    void rejectsNull() {
      assertRejected(null, "BOLT11 invoice must not be null");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "lnbc1invoice", "lntb100test0a1b2c3d", "lnbc100n1pjexamplefake"})
    @DisplayName("placeholder strings used by stubs are not invoices")
    void rejectsPlaceholderStrings(String placeholder) {
      assertThatThrownBy(() -> Bolt11PaymentHashExtractor.extract(placeholder))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("failure messages never echo the invoice")
    void failureMessageDoesNotContainInvoice() {
      String invoice = new InvoiceBuilder("lnbc").paymentHash(hash).paymentHash(hash).build();

      assertThatThrownBy(() -> Bolt11PaymentHashExtractor.extract(invoice))
          .isInstanceOf(IllegalArgumentException.class)
          .message()
          .doesNotContain(invoice.substring(invoice.indexOf('1') + 1, invoice.indexOf('1') + 30));
    }
  }

  private static void assertRejected(String invoice, String message) {
    assertThatThrownBy(() -> Bolt11PaymentHashExtractor.extract(invoice))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(message);
  }

  /** Splits {@code bytes} into five-bit words, zero-padded or truncated to {@code count} words. */
  private static int[] wordsOf(byte[] bytes, int count) {
    int[] words = new int[count];
    int accumulator = 0;
    int bits = 0;
    int index = 0;
    for (byte b : bytes) {
      accumulator = (accumulator << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5 && index < count) {
        bits -= 5;
        words[index++] = (accumulator >>> bits) & 0x1f;
      }
    }
    if (bits > 0 && index < count) {
      words[index] = (accumulator << (5 - bits)) & 0x1f;
    }
    return words;
  }

  /**
   * Test-side bech32 encoder for BOLT #11 shaped strings: a timestamp, tagged fields, a zero
   * signature and a valid bech32 checksum. It is not a full invoice writer and never signs.
   */
  private static final class InvoiceBuilder {
    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GENERATOR = {
      0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
    };

    private final String hrp;
    private final List<Integer> fields = new ArrayList<>();
    private int signatureWords = 104;

    InvoiceBuilder(String hrp) {
      this.hrp = hrp;
    }

    InvoiceBuilder paymentHash(byte[] hash) {
      return field(1, wordsOf(hash, 52));
    }

    InvoiceBuilder field(int type, int[] data) {
      rawWords(type, data.length >>> 5, data.length & 0x1f);
      return rawWords(data);
    }

    InvoiceBuilder rawWords(int... words) {
      for (int word : words) {
        fields.add(word);
      }
      return this;
    }

    InvoiceBuilder signatureWords(int count) {
      this.signatureWords = count;
      return this;
    }

    String build() {
      List<Integer> data = new ArrayList<>();
      for (int i = 0; i < 7; i++) {
        data.add(i + 1);
      }
      data.addAll(fields);
      for (int i = 0; i < signatureWords; i++) {
        data.add(0);
      }
      int checksum = checksum(data);
      var out = new StringBuilder(hrp).append('1');
      for (int word : data) {
        out.append(CHARSET.charAt(word));
      }
      for (int i = 0; i < 6; i++) {
        out.append(CHARSET.charAt((checksum >>> (5 * (5 - i))) & 0x1f));
      }
      return out.toString();
    }

    private int checksum(List<Integer> data) {
      int chk = 1;
      for (int i = 0; i < hrp.length(); i++) {
        chk = step(chk, hrp.charAt(i) >>> 5);
      }
      chk = step(chk, 0);
      for (int i = 0; i < hrp.length(); i++) {
        chk = step(chk, hrp.charAt(i) & 0x1f);
      }
      for (int word : data) {
        chk = step(chk, word);
      }
      for (int i = 0; i < 6; i++) {
        chk = step(chk, 0);
      }
      return chk ^ 1;
    }

    private static int step(int chk, int value) {
      int top = chk >>> 25;
      int next = ((chk & 0x1ffffff) << 5) ^ value;
      for (int i = 0; i < 5; i++) {
        if (((top >>> i) & 1) != 0) {
          next ^= GENERATOR[i];
        }
      }
      return next;
    }
  }
}
