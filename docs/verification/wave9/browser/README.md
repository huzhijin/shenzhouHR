# W9 浏览器上线验收矩阵

当前矩阵合同已建立，真实执行状态为 `NOT_VERIFIED`。

## 固定维度

- 平台：Chrome current/previous、Edge current/previous、批准的 iOS Safari、批准的 Android Chrome。
- 视口：390×844、768×1024、1024×768、1366×768、1440×900、1920×1080、3840×2160。
- 覆盖：允许角色、拒绝角色、loading、empty、error/retry、403、frozen、键盘/焦点、溢出、缓存/撤权、PAYROLL 零发现性。

`browser-matrix.template.json` 只定义待执行合同，没有用 demo 或静态原型伪造截图。当前 11 个 case × 6 个平台 × 7 个视口，共 462 个执行单元；W7 FINAL route、合成身份、MySQL 8.4 环境、浏览器/OS/设备版本、截图或 trace 及 SHA-256 到位后才能填充。

## 结构校验

```bash
python3 scripts/release/browser_matrix.py \
  --matrix docs/verification/wave9/browser/browser-matrix.template.json
```

模板结构应为 `contract_status=PASS`，但 `browser_gate_status=NOT_VERIFIED`。最终矩阵只有同时满足以下条件时才可能得到浏览器叶子 `PASS`：

1. `source_mode=REAL_INTEGRATED`，且 commit 不是占位值；
2. 462 个单元全部记录且没有 `FAIL`/`NOT_VERIFIED`；
3. 每个结果的 run ID、commit、environment、route、identity 与父合同一致；
4. 每个 PASS/FAIL 至少有截图或 trace，并通过相对路径与 SHA-256 校验；
5. console、page error 与网络摘要均已记录。

浏览器叶子通过仍不等于 W9 总门通过；W7、MySQL 8.4、性能、动态威胁、恢复和原生部署门必须在同一 release manifest 中独立通过。
