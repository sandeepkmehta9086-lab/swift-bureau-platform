package com.swiftbureau.mx;

import com.swiftbureau.domain.ChargeBearer;
import com.swiftbureau.payment.PaymentMessage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public final class MxMessageFactory {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private MxMessageFactory() {
    }

    public static String newUetr() {
        return UUID.randomUUID().toString();
    }

    public static String pacs008(PaymentMessage p) {
        UUID id = p.getId() != null ? p.getId() : UUID.randomUUID();
        String msgId = "M" + id.toString().replace("-", "").substring(0, 16);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
                  <FIToFICstmrCdtTrf>
                    <GrpHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                      <NbOfTxs>1</NbOfTxs>
                      <SttlmInf><SttlmMtd>INDA</SttlmMtd></SttlmInf>
                    </GrpHdr>
                    <CdtTrfTxInf>
                      <PmtId>
                        <InstrId>%s</InstrId>
                        <EndToEndId>%s</EndToEndId>
                        <UETR>%s</UETR>
                      </PmtId>
                      <IntrBkSttlmAmt Ccy="%s">%s</IntrBkSttlmAmt>
                      <ChrgBr>%s</ChrgBr>
                      <InstgAgt><FinInstnId><BICFI>%s</BICFI></FinInstnId></InstgAgt>
                      <InstdAgt><FinInstnId><BICFI>%s</BICFI></FinInstnId></InstdAgt>
                      <Dbtr>
                        <Nm>%s</Nm>
                        <PstlAdr>
                          <StrtNm>%s</StrtNm>
                          <TwnNm>%s</TwnNm>
                          <Ctry>%s</Ctry>
                        </PstlAdr>
                      </Dbtr>
                      <Cdtr>
                        <Nm>%s</Nm>
                        <PstlAdr>
                          <StrtNm>%s</StrtNm>
                          <TwnNm>%s</TwnNm>
                          <Ctry>%s</Ctry>
                        </PstlAdr>
                      </Cdtr>
                    </CdtTrfTxInf>
                  </FIToFICstmrCdtTrf>
                </Document>
                """.formatted(
                esc(msgId),
                ISO.format(Instant.now().atOffset(ZoneOffset.UTC)),
                esc(msgId),
                esc(id.toString()),
                esc(p.getUetr()),
                esc(p.getCurrency()),
                p.getAmount().toPlainString(),
                charge(p.getChargeBearer()),
                esc(p.getInstructingAgentBic()),
                esc(p.getInstructedAgentBic()),
                esc(p.getDebtorName()),
                esc(nz(p.getDebtorStreet())),
                esc(p.getDebtorTown()),
                esc(p.getDebtorCountry()),
                esc(p.getCreditorName()),
                esc(nz(p.getCreditorStreet())),
                esc(p.getCreditorTown()),
                esc(p.getCreditorCountry())
        );
    }

    public static String pacs009(PaymentMessage p) {
        return pacs008(p)
                .replace("urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08", "urn:iso:std:iso:20022:tech:xsd:pacs.009.001.08")
                .replace("FIToFICstmrCdtTrf", "FICdtTrf");
    }

    public static String pacs002(PaymentMessage original, String status) {
        String msgId = "A" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
                  <FIToFIPmtStsRpt>
                    <GrpHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                    </GrpHdr>
                    <OrgnlGrpInfAndSts>
                      <OrgnlMsgId>%s</OrgnlMsgId>
                      <OrgnlMsgNmId>%s</OrgnlMsgNmId>
                      <GrpSts>%s</GrpSts>
                    </OrgnlGrpInfAndSts>
                    <TxInfAndSts>
                      <OrgnlUETR>%s</OrgnlUETR>
                      <TxSts>%s</TxSts>
                    </TxInfAndSts>
                  </FIToFIPmtStsRpt>
                </Document>
                """.formatted(
                esc(msgId),
                ISO.format(Instant.now()),
                esc(original.getId().toString()),
                esc(original.getMessageType()),
                esc(status),
                esc(original.getUetr()),
                esc(status)
        );
    }

    public static String camt054(PaymentMessage p, String debitCredit) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.054.001.08">
                  <BkToCstmrDbtCdtNtfctn>
                    <GrpHdr><MsgId>N%s</MsgId><CreDtTm>%s</CreDtTm></GrpHdr>
                    <Ntfctn>
                      <Id>%s</Id>
                      <Acct><Id><Othr><Id>%s</Id></Othr></Id></Acct>
                      <Ntry>
                        <Amt Ccy="%s">%s</Amt>
                        <CdtDbtInd>%s</CdtDbtInd>
                        <NtryDtls><TxDtls><Refs><UETR>%s</UETR></Refs></TxDtls></NtryDtls>
                      </Ntry>
                    </Ntfctn>
                  </BkToCstmrDbtCdtNtfctn>
                </Document>
                """.formatted(
                p.getId().toString().replace("-", "").substring(0, 12),
                ISO.format(Instant.now()),
                p.getId(),
                p.getInstructingAgentBic(),
                p.getCurrency(),
                p.getAmount().toPlainString(),
                debitCredit,
                p.getUetr()
        );
    }

    public static String camt053(String accountRef, String currency, String balance, String bic) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08">
                  <BkToCstmrStmt>
                    <GrpHdr><MsgId>S%s</MsgId><CreDtTm>%s</CreDtTm></GrpHdr>
                    <Stmt>
                      <Id>%s</Id>
                      <Acct><Id><Othr><Id>%s</Id></Othr></Id><Ccy>%s</Ccy></Acct>
                      <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="%s">%s</Amt></Bal>
                    </Stmt>
                  </BkToCstmrStmt>
                </Document>
                """.formatted(UUID.randomUUID().toString().substring(0, 8), ISO.format(Instant.now()), bic, accountRef, currency, currency, balance);
    }

    public static String camt052(String accountRef, String currency, String balance) {
        return camt053(accountRef, currency, balance, "INTRADAY").replace("camt.053.001.08", "camt.052.001.08")
                .replace("BkToCstmrStmt", "BkToCstmrAcctRpt")
                .replace("<Stmt>", "<Rpt>").replace("</Stmt>", "</Rpt>");
    }

    public static String camt056(PaymentMessage original) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.056.001.08">
                  <FIToFIPmtCxlReq>
                    <Assgnmt><Id>R%s</Id></Assgnmt>
                    <Undrlyg>
                      <TxInf>
                        <OrgnlUETR>%s</OrgnlUETR>
                        <CxlRsnInf><Rsn><Prtry>CUST</Prtry></Rsn></CxlRsnInf>
                      </TxInf>
                    </Undrlyg>
                  </FIToFIPmtCxlReq>
                </Document>
                """.formatted(original.getId().toString().substring(0, 8), original.getUetr());
    }

    public static String camt029(PaymentMessage original, String status) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.029.001.09">
                  <RsltnOfInvstgtn>
                    <Assgnmt><Id>I%s</Id></Assgnmt>
                    <Sts><Conf>%s</Conf></Sts>
                    <CxlDtls><OrgnlUETR>%s</OrgnlUETR></CxlDtls>
                  </RsltnOfInvstgtn>
                </Document>
                """.formatted(original.getId().toString().substring(0, 8), esc(status), original.getUetr());
    }

    public static void validateStructuredAddress(PaymentMessage p) {
        if (blank(p.getDebtorTown()) || blank(p.getDebtorCountry()) || blank(p.getCreditorTown()) || blank(p.getCreditorCountry())) {
            throw new com.swiftbureau.common.ApiException(400, "CBPR_ADDRESS",
                    "Structured postal address (town and country) is mandatory on CBPR+ payments from November 2026");
        }
        if (p.getDebtorCountry().length() != 2 || p.getCreditorCountry().length() != 2) {
            throw new com.swiftbureau.common.ApiException(400, "CBPR_ADDRESS", "Country must be ISO 3166-1 alpha-2");
        }
    }

    public static void validateBic(String bic) {
        if (bic == null || !(bic.length() == 8 || bic.length() == 11) || !bic.chars().allMatch(Character::isLetterOrDigit)) {
            throw new com.swiftbureau.common.ApiException(400, "BIC", "BIC must be 8 or 11 alphanumeric characters");
        }
    }

    private static String charge(ChargeBearer bearer) {
        return bearer == null ? "SHAR" : bearer.name();
    }

    private static String esc(String value) {
        return nz(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
