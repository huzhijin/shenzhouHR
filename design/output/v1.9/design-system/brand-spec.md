# shenzhou-hr 品牌映射

> 来源：已确认的 `docs/docs-confirm/ui/design-tokens.*`、`frontend/src/styles/tokens.css` 与本地 `tmp/prd/logo.svg`。以下 OKLCH 数值由已确认颜色等值换算，不构成第二套配色。

## 六个 Open Design 核心 tokens

```css
:root {
  --bg: oklch(0.9810 0.0062 255.47);
  --surface: oklch(1 0 none);
  --fg: oklch(0.2781 0.0296 256.85);
  --muted: oklch(0.5544 0.0407 257.42);
  --border: oklch(0.8690 0.0198 252.89);
  --accent: oklch(0.4167 0.1455 265.45);
}
```

对应关系：

- `--bg` → `--color-canvas`
- `--surface` → `--color-surface`
- `--fg` → `--color-text-primary`
- `--muted` → `--color-text-muted`
- `--border` → `--color-border`
- `--accent` → `--color-brand-primary`

## 字体栈

- Display：`Inter, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`
- Body：`Inter, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`
- Mono：`ui-monospace, "JetBrains Mono", "SF Mono", Menlo, monospace`

## 视觉姿态

1. 以高密度企业作业界面为主，不使用营销式大标题或装饰性叙事。
2. 品牌蓝用于主操作、当前选择与焦点；单屏只保留一个明确主操作。
3. 品牌红只用于危险操作和关键告警，不承担普通强调。
4. 状态必须同时使用文字、形状或图标，不能只靠颜色区分。
5. 页面优先通过对齐、留白和层级组织信息，阴影仅用于真实悬浮层。
