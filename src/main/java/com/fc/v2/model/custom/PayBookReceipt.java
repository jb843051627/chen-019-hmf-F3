package com.fc.v2.model.custom;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.fc.v2.model.auto.THmfPayRow;

/**
 * 末了一笔对账的回话：册首三个数与逐行点出来的数是不是同一回计算给的。
 * 接不平时，{@link #diffRows} 当场列出差在哪几行，不许拿一个成数搪塞。
 *
 * @author fuce
 * @date 2026-10-02
 */
public class PayBookReceipt implements Serializable {
    private static final long serialVersionUID = 1L;

    private String batchNo;

    /** 册首记的：本册几行 */
    private int bookTotal;
    /** 册首记的：销掉几行 */
    private int bookCleared;
    /** 册首记的：挂起几行 */
    private int bookHeld;

    /** 逐行现点的：册上几行 */
    private int rowTotal;
    /** 逐行现点的：已销账几行 */
    private int rowCleared;
    /** 逐行现点的：挂起几行 */
    private int rowHeld;

    /** 销掉的加挂起的等于本册行数，且册首数与逐行数同一次统计咬合 */
    private boolean balanced;

    /** 接不平时差在哪几行（状态不在已销/挂起里的行、被拨回未销的行等） */
    private List<THmfPayRow> diffRows = new ArrayList<>();

    /** 回执栏原话：空册 / 核讫 */
    private String receiptText;

    private String message;

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public int getBookTotal() {
        return bookTotal;
    }

    public void setBookTotal(int bookTotal) {
        this.bookTotal = bookTotal;
    }

    public int getBookCleared() {
        return bookCleared;
    }

    public void setBookCleared(int bookCleared) {
        this.bookCleared = bookCleared;
    }

    public int getBookHeld() {
        return bookHeld;
    }

    public void setBookHeld(int bookHeld) {
        this.bookHeld = bookHeld;
    }

    public int getRowTotal() {
        return rowTotal;
    }

    public void setRowTotal(int rowTotal) {
        this.rowTotal = rowTotal;
    }

    public int getRowCleared() {
        return rowCleared;
    }

    public void setRowCleared(int rowCleared) {
        this.rowCleared = rowCleared;
    }

    public int getRowHeld() {
        return rowHeld;
    }

    public void setRowHeld(int rowHeld) {
        this.rowHeld = rowHeld;
    }

    public boolean isBalanced() {
        return balanced;
    }

    public void setBalanced(boolean balanced) {
        this.balanced = balanced;
    }

    public List<THmfPayRow> getDiffRows() {
        return diffRows;
    }

    public void setDiffRows(List<THmfPayRow> diffRows) {
        this.diffRows = diffRows;
    }

    public String getReceiptText() {
        return receiptText;
    }

    public void setReceiptText(String receiptText) {
        this.receiptText = receiptText;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
