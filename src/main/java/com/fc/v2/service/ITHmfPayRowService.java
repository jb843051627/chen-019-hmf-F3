package com.fc.v2.service;

import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.THmfPayBook;
import com.fc.v2.model.auto.THmfPayRow;
import com.fc.v2.model.custom.PayBookReceipt;

/**
 * 银行交存汇总册核销行 Service接口（batch-process 形状：整批提交，无单条增删改）
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITHmfPayRowService {

    /** 按主键回查单行 */
    THmfPayRow selectTHmfPayRowById(Long id);

    /**
     * 整批提交这一趟核销处理：判定集中在这一趟办完再入库。
     *
     * 册次号进门定死，行次按提交先后自动占定；每行只给一个去向（已销账/挂起）——
     * 销账认户号不认名字，同一户号几笔户面累加，累过该户应缴额的那一行就地挂起并写明原因，
     * 同册其余各行照走。册号第二遍进来只做逐行比对：去向不改、行不加不改，头一遍结果原样留着。
     * 行数超过单册上限整册退回、一行不留；空册（一行都没核掉）回执写“空册”，不许写核讫。
     *
     * 入参与给回保持原样：返回成功落账行数；重复提交返回头一遍行数且不覆账；
     * 超上限返回 -1 且整批不入库；空批次返回 0。
     */
    int submitBatch(String batchNo, List<THmfPayRow> rows);

    /**
     * 该册没有拿到去向（既不是已销账也不是挂起）的行，按行号升序。
     * 正常一册应为空；接不平的账照这里把差行列出来。
     */
    List<THmfPayRow> listErrors(String batchNo);

    /** 回查册首（三数与回执栏都在上面） */
    THmfPayBook selectBook(String batchNo);

    /**
     * 末了一笔对账：册首三个数与逐行销出来的数同一次统计咬合回话；
     * 销掉+挂起=本册行数；接不平当场把差在哪几行列出来。
     */
    PayBookReceipt reconcile(String batchNo);

    /** 翻册行：现行册面（currentOnly=true 只露现行层，往期挂起行不露面）；按册内行次升序。 */
    List<THmfPayRow> listBookRows(String batchNo, boolean currentOnly);

    /** 撤册：确实要改头一遍的账，先走这道手续。撤掉在册册次，返回是否撤成。 */
    boolean withdrawBook(String batchNo, String reason);

    /**
     * 兜底：挂起的行连跨两个对账季还没有去向，挪到往期册页，现行册面不再露它；
     * 往期只放着给人翻。交存日期落在哪一季算哪一季，月底最后一天算本期。
     * 返回这一趟挪下去的行数。
     */
    int sweepHeldToArch(Date today);
}
