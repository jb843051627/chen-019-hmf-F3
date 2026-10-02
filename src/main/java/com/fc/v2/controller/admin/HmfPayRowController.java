package com.fc.v2.controller.admin;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.THmfPayBook;
import com.fc.v2.model.auto.THmfPayRow;
import com.fc.v2.model.custom.PayBookReceipt;
import com.fc.v2.model.custom.PayBookSubmit;
import com.fc.v2.service.ITHmfPayRowService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * 银行交存汇总册核销 Controller
 *
 * 一册流水递进来之后，哪几行已核销、哪几行挂账、哪几行退往期册页，
 * 判定全在服务层那一趟核销处理里办完再入库——页面只递流水、看回话，
 * 既塞不进多余一行也抽不走任何一行。
 *
 * @author fuce
 * @date 2026-10-02
 */
@Api(value = "银行交存汇总册核销")
@Controller
@RequestMapping("/hmfPayBook")
public class HmfPayRowController extends BaseController {

    @Autowired
    private ITHmfPayRowService hmfPayRowService;

    @Log(title = "银行交存册核销提交", action = "submit")
    @ApiOperation(value = "一册流水整批提交（同号第二遍只比对，不改账）", notes = "整册提交核销")
    @PostMapping("/submit")
    @RequiresPermissions("hmf:hmfPayBook:submit")
    @ResponseBody
    public AjaxResult submit(@RequestBody PayBookSubmit submit) {
        if (submit == null || submit.getBatchNo() == null || submit.getBatchNo().trim().isEmpty()) {
            return AjaxResult.error("册次号进门就得定死，缺册次号不收");
        }
        int landed = hmfPayRowService.submitBatch(submit.getBatchNo(), submit.getRows());
        if (landed < 0) {
            return AjaxResult.error("本册行数超过中心定的单册上限，原册退回、一行不留");
        }
        PayBookReceipt rc = hmfPayRowService.reconcile(submit.getBatchNo());
        String msg = rc == null
                ? ("已收" + landed + "行（空册）")
                : rc.getReceiptText() + "：" + rc.getMessage();
        return AjaxResult.success(200, msg, rc);
    }

    @Log(title = "银行交存册撤册", action = "withdraw")
    @ApiOperation(value = "撤册（确实要改头一遍的账，先走这道手续）", notes = "撤册")
    @PostMapping("/withdraw")
    @RequiresPermissions("hmf:hmfPayBook:withdraw")
    @ResponseBody
    public AjaxResult withdraw(@RequestParam String batchNo, @RequestParam(required = false) String reason) {
        boolean ok = hmfPayRowService.withdrawBook(batchNo, reason);
        return ok ? AjaxResult.success("已撤册，同册次号可重报另起一趟")
                : AjaxResult.error("册次号不存在或已不在册，撤不了");
    }

    @ApiOperation(value = "末了一笔对账（三数同源，差行列出来）", notes = "回执核验")
    @GetMapping("/receipt")
    @RequiresPermissions("hmf:hmfPayBook:receipt")
    @ResponseBody
    public AjaxResult receipt(@RequestParam String batchNo) {
        PayBookReceipt rc = hmfPayRowService.reconcile(batchNo);
        if (rc == null) {
            return AjaxResult.error("查无此册");
        }
        return rc.isBalanced()
                ? AjaxResult.successData(200, rc)
                : AjaxResult.error(500, rc.getMessage()).put("data", rc);
    }

    @ApiOperation(value = "翻册行（现行册面；往期挂起行只放着给人翻）", notes = "册内行")
    @GetMapping("/rows")
    @RequiresPermissions("hmf:hmfPayBook:view")
    @ResponseBody
    public AjaxResult rows(@RequestParam String batchNo,
                           @RequestParam(required = false, defaultValue = "true") boolean currentOnly) {
        List<THmfPayRow> rows = hmfPayRowService.listBookRows(batchNo, currentOnly);
        return AjaxResult.successData(200, rows);
    }

    @ApiOperation(value = "册首回话", notes = "册首")
    @GetMapping("/book")
    @RequiresPermissions("hmf:hmfPayBook:view")
    @ResponseBody
    public AjaxResult book(@RequestParam String batchNo) {
        THmfPayBook book = hmfPayRowService.selectBook(batchNo);
        return book == null ? AjaxResult.error("查无此册") : AjaxResult.successData(200, book);
    }

    @Log(title = "挂起行挪往期", action = "sweep")
    @ApiOperation(value = "挂起连跨两个对账季无去向，挪往期册页", notes = "往期兜底")
    @PostMapping("/sweepArch")
    @RequiresPermissions("hmf:hmfPayBook:sweep")
    @ResponseBody
    public AjaxResult sweepArch() {
        return AjaxResult.success("这一趟挪下去" + hmfPayRowService.sweepHeldToArch(null) + "行");
    }
}
