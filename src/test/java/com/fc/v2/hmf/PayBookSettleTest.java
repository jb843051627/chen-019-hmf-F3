package com.fc.v2.hmf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fc.v2.model.auto.THmfAcctCard;
import com.fc.v2.model.auto.THmfPayBook;
import com.fc.v2.model.auto.THmfPayRow;
import com.fc.v2.model.custom.PayBookReceipt;
import com.fc.v2.service.impl.THmfPayRowServiceImpl;

/**
 * 《交存册核销口径》逐条核对：
 * 册次进门定死、行次自动排、销账认户号、同户累加不盖账、超额就地挂起、同册其余照走、
 * 同号第二遍只比对不改账、撤册才许重报、500 上限整册退、空册不写核讫、
 * 挂起跨两个对账季挪往期（月底最后一天算本期）、回执三数同源、接不平列差行。
 */
class PayBookSettleTest {

    private static final int UNCLEARED = 0;
    private static final int CLEARED = 1;
    private static final int HELD = 2;

    private THmfPayRowServiceImpl service;
    private InMemoryMapper<THmfPayRow> rows;
    private InMemoryMapper<THmfPayBook> books;
    private InMemoryMapper<THmfAcctCard> cards;

    /** 定点时钟：进门时刻统一落 2026-10-02。 */
    private long clock = Date.valueOf("2026-10-02").getTime();

    @BeforeEach
    void setUp() throws Exception {
        this.service = new THmfPayRowServiceImpl() {
            @Override
            protected java.util.Date nowTime() {
                return new java.util.Date(clock);
            }
        };
        this.rows = new FakePayRowMapper();
        this.books = new FakePayBookMapper();
        this.cards = new FakeAcctCardMapper();
        inject("hmfPayRowMapper", rows);
        inject("hmfPayBookMapper", books);
        inject("hmfAcctCardMapper", cards);

        card("FH-0001", "14000.00");
        card("FH-0002", "8000.00");
    }

    private void inject(String name, Object value) throws Exception {
        Field f = THmfPayRowServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private void card(String billNo, String fineAmt) {
        THmfAcctCard c = new THmfAcctCard();
        c.setBillNo(billNo);
        c.setSiteNo("JZ00");
        c.setNodeNo(1);
        c.setFineAmt(new BigDecimal(fineAmt));
        c.setDelFlag(0);
        cards.insert(c);
    }

    private THmfPayRow row(String itemCode, String qty) {
        return row(itemCode, qty, "2026-10-01");
    }

    private THmfPayRow row(String itemCode, String qty, String payDate) {
        THmfPayRow r = new THmfPayRow();
        r.setItemCode(itemCode);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        r.setPayDate(payDate == null ? null : Date.valueOf(payDate));
        // 行上自带的册次号、行次都是干扰项：进门只认入参册次号，行次按先后重排。
        r.setBatchNo("纸面乱写的册号");
        r.setRowNo(999);
        return r;
    }

    private List<THmfPayRow> stored(String batchNo) {
        return service.listBookRows(batchNo, false);
    }

    // ---- 行次自动占定，册次进门定死 ----

    @Test
    void rowNumbersAssignedByOrder_andBatchNoFixedAtDoor() {
        int n = service.submitBatch("B-1", java.util.Arrays.asList(
                row("FH-0001", "8000.00"), row("FH-0002", "1000.00"), row("FH-0001", "6000.00")));
        assertEquals(3, n);
        List<THmfPayRow> got = stored("B-1");
        for (int i = 0; i < got.size(); i++) {
            assertEquals(i + 1, got.get(i).getRowNo(), "按先后自动排行次");
            assertEquals("B-1", got.get(i).getBatchNo(), "行上自带的册号不作数");
            assertEquals(0, got.get(i).getArchLayer(), "进门都在现行册面");
        }
    }

    // ---- 同一户两笔：累加，不盖前一笔 ----

    @Test
    void sameAccountTwoPayments_accumulate_eightThousandPlusSixThousand() {
        int n = service.submitBatch("B-1", java.util.Arrays.asList(
                row("FH-0001", "8000.00"), row("FH-0001", "6000.00")));
        assertEquals(2, n);
        List<THmfPayRow> got = stored("B-1");
        assertEquals(CLEARED, got.get(0).getStatus());
        assertEquals(CLEARED, got.get(1).getStatus(), "第二笔进来不盖第一笔，户面记一万四，两行都销");
        assertEquals(0, new BigDecimal("14000.00").compareTo(
                got.get(0).getQty().add(got.get(1).getQty())));
        PayBookReceipt rc = service.reconcile("B-1");
        assertTrue(rc.isBalanced());
        assertEquals("核讫", rc.getReceiptText(), "有行核掉才许写核讫");
        assertEquals(2, rc.getRowCleared());
        assertEquals(0, rc.getRowHeld());
    }

    @Test
    void sameAccountAcrossBooks_accumulates_overpayOnThirdBookHeld() {
        assertEquals(1, service.submitBatch("B-1", java.util.Collections.singletonList(row("FH-0001", "8000.00"))));
        assertEquals(1, service.submitBatch("B-2", java.util.Collections.singletonList(row("FH-0001", "6000.00"))));
        assertEquals(1, service.submitBatch("B-3", java.util.Collections.singletonList(row("FH-0001", "100.00"))));
        assertEquals(CLEARED, stored("B-1").get(0).getStatus());
        assertEquals(CLEARED, stored("B-2").get(0).getStatus(), "第二册进来不盖第一册已销的账");
        THmfPayRow over = stored("B-3").get(0);
        assertEquals(HELD, over.getStatus(), "户面已到一万四，再多一百的那一行就地挂起");
        assertTrue(over.getRemark().contains("超过应缴"));
    }

    // ---- 超额那一行就地挂起，同册其余各行照走 ----

    @Test
    void exceedingRowHeldInPlace_sameBookOtherRowsProceed() {
        int n = service.submitBatch("B-1", java.util.Arrays.asList(
                row("FH-0001", "8000.00"),
                row("FH-0002", "8000.00"),
                row("FH-0001", "7000.00")));
        assertEquals(3, n, "不退回整册重报，3 行全收");
        List<THmfPayRow> got = stored("B-1");
        assertEquals(CLEARED, got.get(0).getStatus(), "头一笔八千照销");
        assertEquals(CLEARED, got.get(1).getStatus(), "别户的行照走不误");
        assertEquals(HELD, got.get(2).getStatus(), "八千加七千超过应缴一万四，这一行挂起");
        assertTrue(got.get(2).getRemark().contains("FH-0001"));
        assertEquals(CLEARED, got.get(0).getStatus(), "挂起不回头拨同册已销行");
        PayBookReceipt rc = service.reconcile("B-1");
        assertTrue(rc.isBalanced());
        assertEquals(3, rc.getRowTotal());
        assertEquals(2, rc.getRowCleared());
        assertEquals(1, rc.getRowHeld(), "销掉 2 加挂起 1 等于本册 3 行");
    }

    // ---- 销账认户号：对不上分户底册的挂起，金额不合法也挂起 ----

    @Test
    void unknownAccount_holdsRow_validRowsStillClear() {
        service.submitBatch("B-1", java.util.Arrays.asList(
                row("FH-9999", "100.00"),
                row("FH-0002", "1000.00"),
                row("FH-0002", "0.00"),
                row("FH-0002", null)));
        List<THmfPayRow> got = stored("B-1");
        assertEquals(HELD, got.get(0).getStatus(), "底册里没这个户号，名字再像也不认");
        assertTrue(got.get(0).getRemark().contains("对不上分户底册"));
        assertEquals(CLEARED, got.get(1).getStatus());
        assertEquals(HELD, got.get(2).getStatus(), "金额为 0 认不到户面");
        assertEquals(HELD, got.get(3).getStatus(), "金额缺失挂起候办");
    }

    // ---- 同号第二遍：只比对，去向不改、行不加不改 ----

    @Test
    void sameBatchSecondTime_compareOnly_neverReopensClearedRows() {
        List<THmfPayRow> first = java.util.Arrays.asList(
                row("FH-0001", "8000.00"), row("FH-0001", "6000.00"));
        service.submitBatch("B-1", first);

        // 银行换了人手，三天里报第二回：库里头一遍的行一个不许动。
        int n2 = service.submitBatch("B-1", java.util.Arrays.asList(
                row("FH-0001", "8000.00"), row("FH-0001", "6000.00")));
        assertEquals(2, n2, "回话仍是头一遍行数");
        assertEquals(2, rows.all().size(), "第二遍一行不加");
        for (THmfPayRow r : stored("B-1")) {
            assertEquals(CLEARED, r.getStatus(), "已销的不能被第二回拨回未销");
        }
        THmfPayBook book = service.selectBook("B-1");
        assertTrue(book.getMatchNote().contains("行行对得上"));

        // 第二遍递的内容有出入：只记比对回话，账照样不动。
        int n3 = service.submitBatch("B-1", java.util.Arrays.asList(
                row("FH-0001", "8000.00"), row("FH-0001", "9999.00"), row("FH-0002", "1.00")));
        assertEquals(2, n3);
        assertEquals(2, rows.all().size(), "有出入也不加行、不改行");
        assertTrue(service.selectBook("B-1").getMatchNote().contains("有出入"));
        assertEquals(CLEARED, stored("B-1").get(1).getStatus(), "第二回带歪的金额盖不了头一遍");
    }

    // ---- 撤册才许改：撤了重报另起一趟 ----

    @Test
    void withdrawThenResubmit_startsFreshTrip() {
        service.submitBatch("B-1", java.util.Collections.singletonList(row("FH-0001", "20000.00")));
        assertEquals(HELD, stored("B-1").get(0).getStatus());
        assertTrue(service.withdrawBook("B-1", "银行户号报错"));
        assertFalse(service.withdrawBook("B-1", null), "撤过的册撤不了第二道");
        assertNull(service.selectBook("B-1"), "撤掉的册不在现行册首里");
        assertTrue(stored("B-1").isEmpty(), "旧行随册留底（软删），现行册面不露");

        int n = service.submitBatch("B-1", java.util.Arrays.asList(
                row("FH-0001", "8000.00"), row("FH-0001", "6000.00")));
        assertEquals(2, n, "撤册后同号重报，另起一趟核销");
        assertEquals(CLEARED, stored("B-1").get(0).getStatus());
        assertEquals(CLEARED, stored("B-1").get(1).getStatus());
    }

    // ---- 单册上限：超了原册退回，一行不留 ----

    @Test
    void overLimitWholeBookRejected_noRowLeft() {
        List<THmfPayRow> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(row("FH-0002", "1.00"));
        }
        assertEquals(-1, service.submitBatch("B-BIG", many));
        assertEquals(0, rows.all().size(), "原册退回，一行不留");
        assertNull(service.selectBook("B-BIG"), "超限的册不立");
    }

    @Test
    void blankBatchOrEmptyRows_nothingLands() {
        assertEquals(0, service.submitBatch("  ", java.util.Collections.singletonList(row("FH-0002", "1.00"))));
        assertEquals(0, service.submitBatch("B-0", new ArrayList<>()));
        assertEquals(0, service.submitBatch("B-0", null));
        assertEquals(0, rows.all().size());
    }

    // ---- 全册没一行能核：回执空册，不许写核讫 ----

    @Test
    void noRowCleared_receiptEmpty_neverDone() {
        service.submitBatch("B-E", java.util.Arrays.asList(
                row("FH-7001", "1.00"), row("FH-7002", "2.00")));
        THmfPayBook book = service.selectBook("B-E");
        assertEquals(Integer.valueOf(0), book.getReceipt(), "空册回执写空册，不许写核讫");
        PayBookReceipt rc = service.reconcile("B-E");
        assertEquals("空册", rc.getReceiptText());
        assertEquals(0, rc.getRowCleared());
        assertEquals(2, rc.getRowHeld(), "挂起也是行，销+挂仍等于本册行数");
        assertTrue(rc.isBalanced());
    }

    // ---- 末了一笔对账：三数同源；接不平当场列差行 ----

    @Test
    void tamperedBookHeader_reconcileListsEveryDiffRow() {
        service.submitBatch("B-1", java.util.Arrays.asList(
                row("FH-0001", "8000.00"), row("FH-0001", "6000.00")));
        assertTrue(service.reconcile("B-1").isBalanced());

        // 册首那个数被手拨过：页面自己点数那一回不算，册首与逐行接不平。
        THmfPayBook tampered = service.selectBook("B-1");
        tampered.setTotalRows(5);
        books.updateById(tampered);
        PayBookReceipt rc = service.reconcile("B-1");
        assertFalse(rc.isBalanced());
        assertEquals(2, rc.getDiffRows().size(), "不许拿成数搪塞，差行当场摆出");
        assertTrue(rc.getMessage().contains("册首总行数5，逐行点出2"));
    }

    @Test
    void rowReopenedToUncleared_reconcileNamesThatRow() {
        service.submitBatch("B-1", java.util.Arrays.asList(
                row("FH-0001", "8000.00"), row("FH-0002", "1000.00")));
        THmfPayRow victim = stored("B-1").get(1);
        victim.setStatus(UNCLEARED); // 第二回报单把已销行拨回未销的事故形状
        rows.updateById(victim);

        PayBookReceipt rc = service.reconcile("B-1");
        assertFalse(rc.isBalanced());
        assertEquals(1, rc.getDiffRows().size());
        assertEquals(victim.getId(), rc.getDiffRows().get(0).getId(), "差在哪一行点到行");
        assertTrue(service.listErrors("B-1").contains(victim));
    }

    // ---- 挂起跨两个对账季：挪往期，月底最后一天算本期 ----

    @Test
    void heldAcrossTwoSeasons_movesToArch_currentFaceHidesIt() {
        service.submitBatch("B-1", java.util.Collections.singletonList(row("FH-0001", "20000.00", "2026-07-31")));
        THmfPayRow held = stored("B-1").get(0);
        assertEquals(HELD, held.getStatus());

        // 交存日期 7 月 31 日算 7 月这一季；到 8 月底只跨过一个对账季，不挪；
        // 进了 9 月就是连跨 7→8、8→9 两个对账季，挪往期。
        assertEquals(0, service.sweepHeldToArch(Date.valueOf("2026-08-31")), "只跨过一个对账季，不挪");
        assertEquals(0, held.getArchLayer());
        clock = Date.valueOf("2026-09-01").getTime();
        assertEquals(1, service.sweepHeldToArch(Date.valueOf("2026-09-01")), "一进第二个对账季即挪往期");
        assertEquals(1, service.selectTHmfPayRowById(held.getId()).getArchLayer());
        assertTrue(service.listBookRows("B-1", true).isEmpty(), "现行册面不再露它");
        assertEquals(1, service.listBookRows("B-1", false).size(), "往期只放着给人翻");

        // 明年开新册不受它牵住：往期挂起行不影响新册核销。
        clock = Date.valueOf("2027-01-05").getTime();
        int n = service.submitBatch("B-NEW", java.util.Collections.singletonList(row("FH-0002", "500.00", "2027-01-04")));
        assertEquals(1, n);
        assertEquals(CLEARED, stored("B-NEW").get(0).getStatus());
    }

    @Test
    void heldOnlyOneSeason_staysCurrent_andClearedRowsNeverSwept() {
        service.submitBatch("B-1", java.util.Arrays.asList(
                row("FH-0001", "8000.00", "2026-08-01"),
                row("FH-0001", "9000.00", "2026-08-31")));
        // 8 月 31 日算 8 月本期；9 月 30 日只跨过一个对账季，不挪。
        assertEquals(0, service.sweepHeldToArch(Date.valueOf("2026-09-30")));
        List<THmfPayRow> got = stored("B-1");
        assertEquals(CLEARED, got.get(0).getStatus());
        assertEquals(HELD, got.get(1).getStatus());
        assertEquals(0, got.get(1).getArchLayer(), "月底最后一天交存的不漏算到下一季");
    }

    // ---- 册首三数与回执 ----

    @Test
    void bookHeaderNumbers_comeFromSameStatsAsRows() {
        service.submitBatch("B-1", java.util.Arrays.asList(
                row("FH-0001", "8000.00"),
                row("FH-0001", "7000.00"),
                row("FH-0002", "1000.00")));
        THmfPayBook book = service.selectBook("B-1");
        assertEquals(Integer.valueOf(3), book.getTotalRows());
        assertEquals(Integer.valueOf(2), book.getClearedRows());
        assertEquals(Integer.valueOf(1), book.getHeldRows(), "三数同一次统计写出：3=2+1");
        assertEquals(Integer.valueOf(1), book.getReceipt());
        assertNotNull(book.getPeriodNo());
        assertTrue(service.listErrors("B-1").isEmpty());
    }
}
