package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.Date;

/**
 * 银行交存汇总册对象 t_hmf_pay_book
 *
 * 一份册一个册次号，进门定死；册面总行数、这趟核销行数、挂账行数三个数
 * 由同一趟核销统计写出，回执与逐行照此回话。
 *
 * @author fuce
 * @date 2026-10-02
 */
@TableName("t_hmf_pay_book")
@ApiModel(value = "THmfPayBook", description = "银行交存汇总册")
public class THmfPayBook implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 册次号（进门定死，一个月来几册各是各的，行不串册） */
    @TableField("batch_no")
    @ApiModelProperty(value = "册次号")
    private String batchNo;

    /** 进门落在哪一对账季（按自然月：年*12+(月-1)；交存日期月底最后一天也算本月，不挪下月） */
    @TableField("period_no")
    @ApiModelProperty(value = "对账季序号（按自然月）")
    private Integer periodNo;

    /** 册面总行数（与逐行销出来的数同一次统计给出） */
    @TableField("total_rows")
    @ApiModelProperty(value = "册面总行数")
    private Integer totalRows;

    /** 这趟核销行数（已销账） */
    @TableField("cleared_rows")
    @ApiModelProperty(value = "已销账行数")
    private Integer clearedRows;

    /** 挂账行数（挂起） */
    @TableField("held_rows")
    @ApiModelProperty(value = "挂起行数")
    private Integer heldRows;

    /** 回执栏 0空册（一行都没核掉） 1核讫（有行核掉）；核讫是报成绩的词，空册不配 */
    @TableField("receipt")
    @ApiModelProperty(value = "回执栏 0空册 1核讫")
    private Integer receipt;

    /** 同号第二遍逐行比对的回话（去向不改、行不加不改，此栏只记比对结果） */
    @TableField("match_note")
    @ApiModelProperty(value = "同号第二遍比对注记")
    private String matchNote;

    /** 册情形 0在册 1已撤册（撤册是改正前的必经手续） */
    @TableField("status")
    @ApiModelProperty(value = "册情形 0在册 1已撤册")
    private Integer status;

    /** 删除标记 0正常 1删除 */
    @TableField("del_flag")
    @ApiModelProperty(value = "删除标记 0正常 1删除")
    private Integer delFlag;

    /** 创建者 */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    /** 更新者 */
    @TableField(value = "update_by", fill = FieldFill.UPDATE)
    @ApiModelProperty(value = "更新者")
    private String updateBy;

    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    /** 备注 */
    @TableField("remark")
    @ApiModelProperty(value = "备注")
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public Integer getPeriodNo() {
        return periodNo;
    }

    public void setPeriodNo(Integer periodNo) {
        this.periodNo = periodNo;
    }

    public Integer getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(Integer totalRows) {
        this.totalRows = totalRows;
    }

    public Integer getClearedRows() {
        return clearedRows;
    }

    public void setClearedRows(Integer clearedRows) {
        this.clearedRows = clearedRows;
    }

    public Integer getHeldRows() {
        return heldRows;
    }

    public void setHeldRows(Integer heldRows) {
        this.heldRows = heldRows;
    }

    public Integer getReceipt() {
        return receipt;
    }

    public void setReceipt(Integer receipt) {
        this.receipt = receipt;
    }

    public String getMatchNote() {
        return matchNote;
    }

    public void setMatchNote(String matchNote) {
        this.matchNote = matchNote;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Integer delFlag) {
        this.delFlag = delFlag;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(String updateBy) {
        this.updateBy = updateBy;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
