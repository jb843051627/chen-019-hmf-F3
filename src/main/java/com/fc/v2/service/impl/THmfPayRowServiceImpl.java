package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.THmfAcctCardMapper;
import com.fc.v2.mapper.auto.THmfPayBookMapper;
import com.fc.v2.mapper.auto.THmfPayRowMapper;
import com.fc.v2.model.auto.THmfAcctCard;
import com.fc.v2.model.auto.THmfPayBook;
import com.fc.v2.model.auto.THmfPayRow;
import com.fc.v2.model.custom.PayBookReceipt;
import com.fc.v2.service.ITHmfPayRowService;

/**
 * 银行交存汇总册核销行 Service业务层处理（batch-process 形状：整批提交）
 *
 * 口径（今年钉死的核销规矩）：
 * 一份册一个册次号，进门就定死；一个月来几册各是各的，这一册的行不混进那一册。
 * 每行进册自动占一个册内行次，按先后排定。每行只有一个去向：已销账、挂起——
 * 销账认户号，名字像不算对上；同一户号几笔进来户面累加，累到超过这户应缴数的，
 * 超的那一行就地挂起并写明为什么，同册其余各行照走，不退整册。
 * 册号第二遍进来只拿行去比库里已有的行，比完就收：去向不改、行不加也不改，
 * 头一遍结果原样留着；确实要改，先走撤册手续。
 *
 * 兜底：单册行数有上限，进册先按整册看，超了原册退回、一行不留；全册没有一行能核，
 * 回执栏写“空册”，不许写核讫。挂起的行连跨两个对账季还没去向，挪往期册页，
 * 现行册面不再露它；交存日期落在哪一季算哪一季，月底最后一天算本期。
 *
 * 末了一笔对账：本册几行、销掉几行、挂起几行，销掉加挂起等于本册行数；
 * 册首数与逐行销出来的数同一次统计给出，接不平当场把差行列出来。
 *
 * 判定集中在 {@link #submitBatch} 这一趟办完再入库；册面总行数、这趟核销行数、
 * 挂账行数三个数出自同一次统计。该方法入参与给回保持原样，不另在出入参上做手脚。
 *
 * @author fuce
 * @date 2026-10-02
 */
@Service
public class THmfPayRowServiceImpl implements ITHmfPayRowService {

    /** 单册行数上限：中心定下的那个数，进册先按整册看 */
    private static final int MAX_ROWS = 500;

    /** 行落地情形：未销（进门暂态，正常一趟走完不应留着） */
    private static final int STATUS_UNCLEARED = 0;
    /** 行落地情形：已销账 */
    private static final int STATUS_CLEARED = 1;
    /** 行落地情形：挂起 */
    private static final int STATUS_HELD = 2;

    /** 册层：现行册面 */
    private static final int LAYER_CURRENT = 0;
    /** 册层：往期册页（只放着给人翻） */
    private static final int LAYER_ARCH = 1;

    /** 回执栏：空册（一行都没核掉，不配写核讫） */
    private static final int RECEIPT_EMPTY = 0;
    /** 回执栏：核讫（报成绩的词，得有行核掉才许写） */
    private static final int RECEIPT_DONE = 1;

    /** 册情形：在册 */
    private static final int BOOK_ACTIVE = 0;
    /** 册情形：已撤册（改正前的必经手续） */
    private static final int BOOK_WITHDRAWN = 1;

    /** 挂起连跨几个对账季还没去向，即挪往期 */
    private static final int AGED_SEASONS = 2;

    @javax.annotation.Resource
    private THmfPayRowMapper hmfPayRowMapper;

    @javax.annotation.Resource
    private THmfPayBookMapper hmfPayBookMapper;

    @javax.annotation.Resource
    private THmfAcctCardMapper hmfAcctCardMapper;

    @Override
    public THmfPayRow selectTHmfPayRowById(Long id) {
        return this.hmfPayRowMapper.selectById(id);
    }

    // ---------------------------------------------------------------- 这一趟核销处理

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int submitBatch(String batchNo, List<THmfPayRow> rows) {
        if (isBlank(batchNo) || rows == null || rows.isEmpty()) {
            return 0;
        }
        String no = batchNo.trim();

        THmfPayBook existing = findBook(no);
        if (existing != null && existing.getStatus() != null && existing.getStatus() == BOOK_ACTIVE) {
            // 册号第二遍进来只做一件事：拿行去比库里已有的行。
            // 去向不改、行不加也不改，头一遍的结果原样留着。
            return compareOnly(existing, rows);
        }

        // 进册先按整册看：行数超上限，原册退回、一行不留（册也不立）。
        if (rows.size() > MAX_ROWS) {
            return -1;
        }

        // 户号→分户底册应缴额：销账认户号，名字像不算对上。
        Map<String, THmfAcctCard> accounts = loadAccounts();
        // 户号→此前各册已销账累计：户面上的钱累加，绝不拿第二笔盖掉第一笔。
        Map<String, BigDecimal> paidBefore = clearedTotalsByAccount();
        // 本趟各户已销账累计（同册头一笔八千、第二笔六千，户面记一万四）。
        Map<String, BigDecimal> paidThisTrip = new HashMap<>();

        List<THmfPayRow> landed = new ArrayList<>(rows.size());
        int seq = 0;
        for (THmfPayRow incoming : rows) {
            seq++;
            THmfPayRow r = new THmfPayRow();
            // 册次号进门定死、行次按先后自动占定：行上自带的号一律不作数。
            r.setBatchNo(no);
            r.setRowNo(seq);
            r.setItemCode(incoming == null || incoming.getItemCode() == null
                    ? null : incoming.getItemCode().trim());
            r.setQty(incoming == null ? null : incoming.getQty());
            r.setPayDate(incoming == null ? null : truncateToDay(incoming.getPayDate()));
            r.setStatus(STATUS_UNCLEARED);
            r.setArchLayer(LAYER_CURRENT);
            r.setDelFlag(0);

            String code = r.getItemCode();
            THmfAcctCard card = code == null ? null : accounts.get(code);
            BigDecimal qty = r.getQty();
            if (incoming == null || code == null) {
                hold(r, "空行进册（户号缺失），认不到户面，挂起候办");
            } else if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                hold(r, "本行到账金额不合法（" + qty + "），认不到户面，挂起候办");
            } else if (card == null) {
                hold(r, "户号“" + code + "”对不上分户底册，销账只认户号，挂起候核");
            } else if (card.getFineAmt() == null) {
                hold(r, "户号“" + code + "”应缴额点不出来，没法比户面，挂起候办");
            } else {
                BigDecimal before = paidBefore.getOrDefault(code, BigDecimal.ZERO)
                        .add(paidThisTrip.getOrDefault(code, BigDecimal.ZERO));
                BigDecimal after = before.add(qty);
                if (after.compareTo(card.getFineAmt()) > 0) {
                    // 累到超过这户应缴数：超的这一行就地挂起，同册其余各行照走。
                    hold(r, "户号“" + code + "”累交" + after.toPlainString()
                            + "已超过应缴" + card.getFineAmt().toPlainString()
                            + "，本行不盖前账，就地挂起候办");
                } else {
                    r.setStatus(STATUS_CLEARED);
                    paidThisTrip.merge(code, qty, BigDecimal::add);
                }
            }
            landed.add(r);
        }

        // 三个数同一次统计点出：册面总行数、这趟核销行数、挂账行数，绝不分两回读。
        int totalRows = landed.size();
        int clearedRows = 0;
        int heldRows = 0;
        for (THmfPayRow r : landed) {
            if (r.getStatus() == STATUS_CLEARED) {
                clearedRows++;
            } else if (r.getStatus() == STATUS_HELD) {
                heldRows++;
            }
        }

        for (THmfPayRow r : landed) {
            this.hmfPayRowMapper.insert(r);
        }

        // 全册没有一行能核，回执那一栏写“空册”；核讫是报成绩的词，空册不配。
        THmfPayBook book = existing == null ? new THmfPayBook() : existing;
        book.setBatchNo(no);
        book.setPeriodNo(seasonKey(nowTime()));
        book.setTotalRows(totalRows);
        book.setClearedRows(clearedRows);
        book.setHeldRows(heldRows);
        book.setReceipt(clearedRows > 0 ? RECEIPT_DONE : RECEIPT_EMPTY);
        book.setMatchNote(null);
        book.setStatus(BOOK_ACTIVE);
        book.setDelFlag(0);
        if (existing == null) {
            this.hmfPayBookMapper.insert(book);
        } else {
            this.hmfPayBookMapper.updateById(book);
        }
        return totalRows;
    }

    /** 同号第二遍：逐行比库里已有的行，比完就收。只把比对回话记在册首，账一个字不动。 */
    private int compareOnly(THmfPayBook book, List<THmfPayRow> incoming) {
        List<THmfPayRow> stored = listBookRows(book.getBatchNo(), false);
        int diffs = 0;
        int compared = Math.min(stored.size(), incoming.size());
        for (int i = 0; i < compared; i++) {
            THmfPayRow s = stored.get(i);
            THmfPayRow in = incoming.get(i);
            String inCode = in == null || in.getItemCode() == null ? null : in.getItemCode().trim();
            BigDecimal sq = s.getQty();
            BigDecimal iq = in == null ? null : in.getQty();
            boolean sameCode = s.getItemCode() == null ? inCode == null
                    : s.getItemCode().equals(inCode);
            boolean sameQty = sq == null ? iq == null : iq != null && sq.compareTo(iq) == 0;
            if (!sameCode || !sameQty) {
                diffs++;
            }
        }
        if (incoming.size() != stored.size()) {
            diffs += Math.abs(incoming.size() - stored.size());
        }
        String note = "同号第二遍递入" + incoming.size() + "行，照原册行次逐行比对" + stored.size()
                + "行：" + (diffs == 0 ? "行行对得上" : diffs + "行有出入")
                + "。去向不改、行不加也不改，头一遍结果原样留着；确实要改，先走撤册手续。";
        book.setMatchNote(note);
        this.hmfPayBookMapper.updateById(book);
        return stored.size();
    }

    // ---------------------------------------------------------------- 末了一笔对账

    @Override
    public PayBookReceipt reconcile(String batchNo) {
        if (isBlank(batchNo)) {
            return null;
        }
        THmfPayBook book = findBook(batchNo.trim());
        if (book == null) {
            return null;
        }
        // 往期层的挂起行仍是这一册的行，回执点数一并点上；现行册面只是不露面。
        List<THmfPayRow> rows = listBookRows(book.getBatchNo(), false);

        int rowTotal = rows.size();
        int rowCleared = 0;
        int rowHeld = 0;
        List<THmfPayRow> bad = new ArrayList<>();
        for (THmfPayRow r : rows) {
            if (r.getStatus() != null && r.getStatus() == STATUS_CLEARED) {
                rowCleared++;
            } else if (r.getStatus() != null && r.getStatus() == STATUS_HELD) {
                rowHeld++;
            } else {
                bad.add(r);
            }
        }

        PayBookReceipt rc = new PayBookReceipt();
        rc.setBatchNo(book.getBatchNo());
        rc.setBookTotal(nz(book.getTotalRows()));
        rc.setBookCleared(nz(book.getClearedRows()));
        rc.setBookHeld(nz(book.getHeldRows()));
        rc.setRowTotal(rowTotal);
        rc.setRowCleared(rowCleared);
        rc.setRowHeld(rowHeld);
        rc.setReceiptText(nz(book.getReceipt()) == RECEIPT_DONE ? "核讫" : "空册");

        boolean headerMatches = rc.getBookTotal() == rowTotal
                && rc.getBookCleared() == rowCleared
                && rc.getBookHeld() == rowHeld;
        boolean sumCloses = rowCleared + rowHeld == rowTotal;
        boolean balanced = headerMatches && sumCloses && bad.isEmpty();
        rc.setBalanced(balanced);

        if (!balanced) {
            // 两边接不平，把差在哪几行当场列出来：去向不明的行先列；
            // 册首数与逐行数对不上的，整册逐行摆出，不许拿一个成数搪塞。
            if (!bad.isEmpty()) {
                rc.setDiffRows(bad);
            } else {
                rc.setDiffRows(new ArrayList<>(rows));
            }
            List<String> gaps = new ArrayList<>();
            if (rc.getBookTotal() != rowTotal) {
                gaps.add("册首总行数" + rc.getBookTotal() + "，逐行点出" + rowTotal);
            }
            if (rc.getBookCleared() != rowCleared) {
                gaps.add("册首核销" + rc.getBookCleared() + "，逐行点出" + rowCleared);
            }
            if (rc.getBookHeld() != rowHeld) {
                gaps.add("册首挂起" + rc.getBookHeld() + "，逐行点出" + rowHeld);
            }
            if (!sumCloses) {
                gaps.add("销掉" + rowCleared + "+挂起" + rowHeld + "≠本册" + rowTotal);
            }
            rc.setMessage("账接不平：" + String.join("；", gaps) + "。差行已列出。");
        } else {
            rc.setMessage("本册" + rowTotal + "行，销掉" + rowCleared + "行，挂起" + rowHeld
                    + "行；销掉加挂起等于本册行数，册首与逐行同一次统计咬合。");
        }
        return rc;
    }

    // ---------------------------------------------------------------- 翻册 / 撤册 / 往期

    @Override
    public List<THmfPayRow> listBookRows(String batchNo, boolean currentOnly) {
        if (isBlank(batchNo)) {
            return new ArrayList<>();
        }
        QueryWrapper<THmfPayRow> qw = new QueryWrapper<THmfPayRow>()
                .eq("batch_no", batchNo.trim()).eq("del_flag", 0);
        if (currentOnly) {
            qw.eq("arch_layer", LAYER_CURRENT);
        }
        List<THmfPayRow> rows = this.hmfPayRowMapper.selectList(qw.orderByAsc("row_no"));
        return rows == null ? new ArrayList<>() : rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean withdrawBook(String batchNo, String reason) {
        if (isBlank(batchNo)) {
            return false;
        }
        THmfPayBook book = findBook(batchNo.trim());
        if (book == null || book.getStatus() == null || book.getStatus() != BOOK_ACTIVE) {
            return false;
        }
        // 撤册是改正前的必经手续：旧行一律不抹，落删除标记留底；同册次号重报时另起头一遍的账。
        for (THmfPayRow r : listBookRows(book.getBatchNo(), false)) {
            r.setDelFlag(1);
            appendRemark(r, "本册撤册（" + (isBlank(reason) ? "未写由头" : reason.trim()) + "），此行随册留底");
            this.hmfPayRowMapper.updateById(r);
        }
        book.setStatus(BOOK_WITHDRAWN);
        appendBookRemark(book, "已撤册（" + (isBlank(reason) ? "未写由头" : reason.trim()) + "），同册次号重报另起一趟");
        // 撤册是改正前的必经手续：册与旧行一律不抹（软删留底），同册次号重报另立册首、另起一趟。
        book.setDelFlag(1);
        this.hmfPayBookMapper.updateById(book);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int sweepHeldToArch(Date today) {
        Date at = today == null ? nowTime() : today;
        List<THmfPayRow> held = this.hmfPayRowMapper.selectList(new QueryWrapper<THmfPayRow>()
                .eq("status", STATUS_HELD).eq("arch_layer", LAYER_CURRENT).eq("del_flag", 0));
        int moved = 0;
        int currentSeason = seasonKey(at);
        for (THmfPayRow r : held) {
            // 哪一行的交存日期落在哪一季就算哪一季；没有交存日期的退回册进门时刻兜底。
            Date base = r.getPayDate() != null ? r.getPayDate() : r.getCreateTime();
            if (base == null) {
                continue;
            }
            if (currentSeason - seasonKey(base) >= AGED_SEASONS) {
                r.setArchLayer(LAYER_ARCH);
                appendRemark(r, "挂起连跨两个对账季还没有去向，挪往期册页；现行册面不再露，明年开新册不受它牵住");
                this.hmfPayRowMapper.updateById(r);
                moved++;
            }
        }
        return moved;
    }

    @Override
    public List<THmfPayRow> listErrors(String batchNo) {
        List<THmfPayRow> rows = listBookRows(batchNo, false);
        List<THmfPayRow> bad = new ArrayList<>();
        for (THmfPayRow r : rows) {
            if (r.getStatus() == null || (r.getStatus() != STATUS_CLEARED && r.getStatus() != STATUS_HELD)) {
                bad.add(r);
            }
        }
        return bad;
    }

    @Override
    public THmfPayBook selectBook(String batchNo) {
        return isBlank(batchNo) ? null : findBook(batchNo.trim());
    }

    // ---------------------------------------------------------------- 私有帮手

    /** 记账时刻：生产取当前时刻；留一道口子给定点核验/测试。 */
    protected Date nowTime() {
        return new Date();
    }

    private void hold(THmfPayRow r, String why) {
        r.setStatus(STATUS_HELD);
        r.setRemark(why);
    }

    /** 户号→现行分户账卡（同户多版取版次序最新的那张）。 */
    private Map<String, THmfAcctCard> loadAccounts() {
        List<THmfAcctCard> cards = this.hmfAcctCardMapper.selectList(new QueryWrapper<THmfAcctCard>()
                .eq("del_flag", 0));
        Map<String, THmfAcctCard> map = new HashMap<>();
        for (THmfAcctCard c : cards) {
            if (isBlank(c.getBillNo())) {
                continue;
            }
            String code = c.getBillNo().trim();
            THmfAcctCard old = map.get(code);
            if (old == null || nz(c.getNodeNo()) >= nz(old.getNodeNo())) {
                map.put(code, c);
            }
        }
        return map;
    }

    /** 各户在此前进册、已销账（且未沉往期）的累计金额。 */
    private Map<String, BigDecimal> clearedTotalsByAccount() {
        List<THmfPayRow> cleared = this.hmfPayRowMapper.selectList(new QueryWrapper<THmfPayRow>()
                .eq("status", STATUS_CLEARED).eq("del_flag", 0));
        Map<String, BigDecimal> totals = new HashMap<>();
        for (THmfPayRow r : cleared) {
            if (isBlank(r.getItemCode()) || r.getQty() == null) {
                continue;
            }
            String code = r.getItemCode().trim();
            totals.merge(code, r.getQty(), BigDecimal::add);
        }
        return totals;
    }

    private THmfPayBook findBook(String batchNo) {
        List<THmfPayBook> books = this.hmfPayBookMapper.selectList(new QueryWrapper<THmfPayBook>()
                .eq("batch_no", batchNo).eq("del_flag", 0));
        if (books == null || books.isEmpty()) {
            return null;
        }
        // 同册次号理论上只一行；在册的优先。
        Collections.sort(books, new Comparator<THmfPayBook>() {
            @Override
            public int compare(THmfPayBook a, THmfPayBook b) {
                return Integer.compare(nz(a.getStatus()), nz(b.getStatus()));
            }
        });
        return books.get(0);
    }

    /** 对账季按自然月：年*12+月序（0基）。月底最后一天天然算本月，绝不漏算到下一季。 */
    private static int seasonKey(Date date) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH);
    }

    private static Date truncateToDay(Date date) {
        if (date == null) {
            return null;
        }
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private void appendRemark(THmfPayRow r, String line) {
        String old = r.getRemark();
        r.setRemark(isBlank(old) ? line : old + "\n" + line);
    }

    private void appendBookRemark(THmfPayBook b, String line) {
        String old = b.getRemark();
        b.setRemark(isBlank(old) ? line : old + "\n" + line);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
