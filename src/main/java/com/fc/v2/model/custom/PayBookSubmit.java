package com.fc.v2.model.custom;

import java.io.Serializable;
import java.util.List;

import com.fc.v2.model.auto.THmfPayRow;

/**
 * 一册流水递进来的入参：册次号 + 本册逐行。
 * 判定集中在服务层那一趟核销处理，页面既塞不进多余一行也抽不走任何一行。
 *
 * @author fuce
 * @date 2026-10-02
 */
public class PayBookSubmit implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 册次号（进门定死，行上自带的册次号一律不作数） */
    private String batchNo;

    /** 本册流水（行次由服务层按先后自动占定，不传行次） */
    private List<THmfPayRow> rows;

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public List<THmfPayRow> getRows() {
        return rows;
    }

    public void setRows(List<THmfPayRow> rows) {
        this.rows = rows;
    }
}
