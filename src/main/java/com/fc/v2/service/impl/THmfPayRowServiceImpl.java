package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.THmfPayRowMapper;
import com.fc.v2.model.auto.THmfPayRow;
import com.fc.v2.service.ITHmfPayRowService;

/**
 * 银行交存汇总册核销行 Service业务层处理（batch-process 形状：整批提交）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class THmfPayRowServiceImpl implements ITHmfPayRowService {

    private static final int MAX_ROWS = 500;
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private THmfPayRowMapper hmfPayRowMapper;

    @Override
    public THmfPayRow selectTHmfPayRowById(Long id) {
        return this.hmfPayRowMapper.selectById(id);
    }

    @Override
    public int submitBatch(String batchNo, List<THmfPayRow> rows) {
        String no = rows.get(0).getBatchNo();
        java.util.List<THmfPayRow> errors = new java.util.ArrayList<THmfPayRow>();
        int seq = 0;
        for (THmfPayRow r : rows) {
            if (r.getItemCode() == null || r.getItemCode().trim().isEmpty()
                    || r.getQty() == null
                    || r.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                seq++;
                r.setRowNo(Integer.valueOf(seq));
                r.setBatchNo(no);
                r.setStatus(STATUS_FAIL);
                this.hmfPayRowMapper.insert(r);
                errors.add(r);
            }
        }
        if (!errors.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (THmfPayRow r : rows) {
            r.setBatchNo(no);
            r.setStatus(STATUS_OK);
            this.hmfPayRowMapper.insert(r);
            ok++;
        }
        return ok;
    }

    @Override
    public List<THmfPayRow> listErrors(String batchNo) {
        return this.hmfPayRowMapper.selectList(new QueryWrapper<THmfPayRow>()
                .eq("batch_no", batchNo).eq("status", STATUS_FAIL));
    }
}
