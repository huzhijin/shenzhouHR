(() => {
    'use strict';
    const STORAGE_KEY = 'shenzhou-hr-v19-demo-state';
    const DEFAULT_STATE = {
        schemaVersion: 'V19-DEMO-1', mode: 'PROTOTYPE_SIMULATION',
        context: { role: 'attendance', month: '2026-07', department: '制造一部', employee: 'EMP-DEMO-001', batch: 'ATT-XLS-DEMO-001', filter: 'all', return: '/workbench' },
        organization: { batch: { id: 'ORG-INIT-DEMO-001', status: 'ready_to_publish', total: 126, valid: 124, errors: 2, publishedAt: null } },
        timeAccount: { batch: { id: 'TIME-INIT-DEMO-001', status: 'ready_to_publish', total: 81, valid: 80, errors: 1, publishedAt: null }, EMP_DEMO_001: { annualLeaveHours: 80, compTimeHours: 0, workTimeHours: 0 } },
        attendance: { batch: { id: 'ATT-XLS-DEMO-001', status: 'ready_to_publish', site: '离线厂区 A', total: 428, valid: 412, errors: 4, duplicates: 8, suspectedDuplicates: 4, unmatchedEmployees: 2, unmappedDevices: 1, frozen: 0, publishable: 412, publishedAt: null }, event: { id: 'ATT-EVENT-DEMO-001', employee: 'EMP-DEMO-001', date: '2026-07-18', segment: '13:00–17:30', source: 'ATT-XLS-DEMO-001', punchIn: '13:02', punchOut: null, exception: 'missing_out', correctionDue: '2026-07-25 23:59', status: 'pending_correction' } },
        rule: { id: 'ATT-POLICY-DEMO-V3', version: 'V3', status: 'draft', effectiveFrom: '2026-08-01', scope: '制造一部 / 离线厂区 A', lateGrace: { count: 1, minutes: 15, period: 'calendar_month', usedByEmployee: 1 }, meal: { enabled: true, window: '18:00–19:00', minutes: 30 }, missingPunch: { days: 7, overdueImpact: 'missing_segment_only' } },
        sources: { online: { id: 'DEVICE-DEMO-01', status: 'healthy', watermark: '2026-07-22 14:18' }, oa: { status: 'healthy', watermark: '2026-07-22 14:12', readOnly: true } },
        exceptions: { total: 7, missingPunch: 3, late: 2, meal: 1, other: 1, resolved: 0 },
        recalculation: { status: 'not_started', changedRecords: 0 },
        monthClose: { month: '2026-07', status: 'blocked', progress: 72, blockingItems: 3, closedAt: null },
        annualLeave: { employee: 'EMP-DEMO-001', eligible: true, qualificationMonths: 24, cumulativeServiceMonths: 120, tier: '满 10 年且不足 20 年', days: 10, hours: 80, grantDate: '2026-07-15', expiryDate: '2027-07-14' },
        feedback: { id: 'FEEDBACK-DEMO-001', employee: 'EMP-DEMO-001', status: '待部门确认', createdAt: '2026-07-20 09:16' }
    };
    const ROLE_LABELS = { management: '公司管理层', hr: 'HR 管理员', attendance: '考勤管理员', lead: '部门负责人', employee: '普通员工', system: '系统管理员', auditor: '审计员' };
    const ROLE_SCOPES = { management: '授权公司汇总', hr: '江苏公司 · 组织与人员', attendance: '江苏公司 · 授权考勤组', lead: '制造一部', employee: '仅本人', system: '技术配置与健康状态', auditor: '授权审计范围 · 只读' };
    const artifactFile = (name) => ['v19-od-', name, '.html'].join('');
    const shiftsArtifact = ['06-attendance', 'groups-shifts'].join('-');
    const ROUTES = {
        '/login': artifactFile('01-login-shell'),
        '/workbench': artifactFile('02-role-workbench'),
        '/people/import': artifactFile('03-people-initial-import'),
        '/people/organization': artifactFile('04-organization-employment'),
        '/people/employees': artifactFile('04-organization-employment'),
        '/people/employees/EMP-DEMO-001': artifactFile('04-organization-employment'),
        '/rules': artifactFile('05-rule-center'),
        '/rules/attendance-groups': artifactFile(shiftsArtifact),
        '/rules/shifts': artifactFile(shiftsArtifact),
        '/rules/calendars': artifactFile(shiftsArtifact),
        '/rules/attendance-policy/ATT-POLICY-DEMO-V3': artifactFile('07-attendance-policies'),
        '/rules/leave': artifactFile('08-leave-annual-policy'),
        '/rules/annual-leave': artifactFile('08-leave-annual-policy'),
        '/rules/time-accounts': artifactFile('09-time-accounts'),
        '/rules/time-accounts/import': artifactFile('09-time-accounts'),
        '/sources/attendance-excel': artifactFile('10a-offline-punch-import'),
        '/sources/attendance-excel/ATT-XLS-DEMO-001': artifactFile('10a-offline-punch-import'),
        '/sources/online': artifactFile('10b-online-oa-sources'),
        '/sources/oa': artifactFile('10b-online-oa-sources'),
        '/sources/jobs': artifactFile('10b-online-oa-sources'),
        '/attendance/daily': artifactFile('11-attendance-evidence'),
        '/attendance/evidence/ATT-EVENT-DEMO-001': artifactFile('11-attendance-evidence'),
        '/attendance/exceptions': artifactFile('12-exceptions-close-reports'),
        '/attendance/recalc': artifactFile('12-exceptions-close-reports'),
        '/attendance/close/2026-07': artifactFile('12-exceptions-close-reports'),
        '/attendance/reports': artifactFile('12-exceptions-close-reports'),
        '/me/today': artifactFile('13-employee-mobile'),
        '/me/records': artifactFile('13-employee-mobile'),
        '/me/leave': artifactFile('13-employee-mobile'),
        '/me/feedback': artifactFile('13-employee-mobile'),
        '/access/accounts': artifactFile('14-access-audit-ops'),
        '/access/roles': artifactFile('14-access-audit-ops'),
        '/access/audit': artifactFile('14-access-audit-ops'),
        '/ops/health': artifactFile('14-access-audit-ops'),
        '/display/attendance': artifactFile('15-attendance-screen'),
        '/qa/handoff': artifactFile('16-qa-handoff')
    };
    const NAV = [
        { label: '核心工作', items: [['工作台', '/workbench', 'home', 'management,hr,attendance,lead,employee,system,auditor']] },
        { label: '组织与规则', items: [['组织与人员', '/people/organization', 'users', 'hr,attendance,lead,auditor'], ['规则与排班', '/rules', 'sliders', 'hr,attendance,auditor']] },
        { label: '数据与考勤', items: [['数据接入', '/sources/online', 'database', 'attendance,system,auditor'], ['考勤管理', '/attendance/daily', 'clock', 'management,hr,attendance,lead,employee,auditor'], ['员工服务', '/me/today', 'user', 'employee']] },
        { label: '治理与运维', items: [['权限与审计', '/access/accounts', 'shield', 'system,auditor'], ['系统运维', '/ops/health', 'settings', 'system,auditor']] }
    ];
    const ICON_NAMES = new Set(['menu', 'search', 'home', 'users', 'sliders', 'database', 'clock', 'user', 'shield', 'settings', 'x', 'check', 'alert', 'file', 'message', 'refresh', 'logout']);
    const $ = (s, r = document) => r.querySelector(s), $$ = (s, r = document) => [...r.querySelectorAll(s)];
    const clone = (v) => JSON.parse(JSON.stringify(v));
    const icon = (name) => {
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('class', 'icon');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('aria-hidden', 'true');
        const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
        use.setAttribute('href', `assets/v19-icons.svg#icon-${ICON_NAMES.has(name) ? name : 'file'}`);
        svg.append(use);
        return svg;
    };
    function deepMerge(target, source) { Object.entries(source || {}).forEach(([k, v]) => { if (v && typeof v === 'object' && !Array.isArray(v)) {
        target[k] = deepMerge(target[k] || {}, v);
    }
    else
        target[k] = v; }); return target; }
    function load() { try {
        const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null');
        return saved && saved.schemaVersion === DEFAULT_STATE.schemaVersion ? deepMerge(clone(DEFAULT_STATE), saved) : clone(DEFAULT_STATE);
    }
    catch (error) {
        console.warn('无法读取原型演示状态，已恢复默认值。', error instanceof Error ? error.name : 'UnknownError');
        return clone(DEFAULT_STATE);
    } }
    let state = load();
    function persist() { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); }
    function get(path) { return path.split('.').reduce((a, k) => a == null ? undefined : a[k], state); }
    function setContextFromQuery() { const q = new URLSearchParams(location.search); ['role', 'month', 'department', 'employee', 'batch', 'filter', 'return'].forEach(k => { if (q.has(k))
        state.context[k] = q.get(k); }); persist(); }
    function currentRoute() { return new URLSearchParams(location.search).get('route') || document.body.dataset.route || '/workbench'; }
    function params(extra = {}) { const c = { ...state.context, ...extra }; const q = new URLSearchParams(); ['role', 'month', 'department', 'employee', 'batch', 'filter', 'return'].forEach(k => { if (c[k])
        q.set(k, c[k]); }); return q; }
    function navigate(route, extra = {}) { const file = ROUTES[route]; if (!file) {
        toast('原型未接入', `路由 ${route} 尚未注册。`);
        return;
    } const q = params({ ...extra, return: extra.return || currentRoute() }); q.set('route', route); location.href = `${file}?${q.toString()}`; }
    function update(patch, message) { deepMerge(state, patch); persist(); renderBindings(); document.dispatchEvent(new CustomEvent('v19:statechange', { detail: state })); if (message)
        toast('演示状态已更新', message); }
    function reset() { state = clone(DEFAULT_STATE); persist(); location.href = `${artifactFile('02-role-workbench')}?route=%2Fworkbench&role=attendance&month=2026-07&department=${encodeURIComponent('制造一部')}`; }
    function renderBindings() { $$('[data-bind]').forEach(el => { const value = get(el.dataset.bind); if (value !== undefined && value !== null)
        el.textContent = value; }); $$('[data-show-when]').forEach(el => { const [path, value] = el.dataset.showWhen.split('='); el.hidden = String(get(path)) !== value; }); $$('[data-hide-when]').forEach(el => { const [path, value] = el.dataset.hideWhen.split('='); el.hidden = String(get(path)) === value; }); }
    function toast(title, copy) { let region = $('#v19-toast-region'); if (!region) {
        region = document.createElement('div');
        region.id = 'v19-toast-region';
        region.className = 'toast-region';
        region.setAttribute('role', 'status');
        region.setAttribute('aria-live', 'polite');
        document.body.append(region);
    } region.replaceChildren(element('div', { className: 'toast' }, icon('check'), element('div', {}, element('strong', { text: title }), element('span', { text: copy })))); clearTimeout(toast.timer); toast.timer = setTimeout(() => region.replaceChildren(), 4200); }
    const STATE_COPY = { loading: ['LOADING', '正在加载授权范围内的数据', '切换角色或范围时不会显示上一范围内容。'], empty: ['EMPTY', '当前筛选没有数据', '调整筛选条件或返回上一层继续。'], failure: ['REQUEST_FAILED', '页面加载失败', '已保留安全上下文，可重试或返回工作台。'], forbidden: ['403 · ACCESS_DENIED', '你没有权限访问此页面', '系统不会泄露目标对象是否存在、数量或字段。'], conflict: ['CONFLICT', '检测到配置冲突', '请查看作用范围、优先级和生效期后再继续。'], frozen: ['PERIOD_FROZEN', '当前期间已冻结', '可以查看和预检，但不能静默发布或改写历史结果。'], processing: ['PROCESSING', '任务处理中', '可安全离开本页，处理结果会保留在批次时间线。'], partial: ['PARTIAL_SUCCESS', '部分记录已完成', '查看失败记录、下载错误报告后可继续处理。'], success: ['SUCCESS', '操作已完成', '状态、时间线和关联页面已同步更新。'] };
    function showPageState(kind) { let layer = $('#v19-state-layer'); if (kind === 'normal') {
        if (layer)
            layer.hidden = true;
        return;
    } const c = STATE_COPY[kind] || STATE_COPY.failure; if (!layer) {
        layer = document.createElement('div');
        layer.id = 'v19-state-layer';
        layer.className = 'state-layer';
        layer.dataset.odId = 'page-state-layer';
        document.body.append(layer);
    } layer.hidden = false; layer.replaceChildren(element('div', { className: 'state-card' }, kind === 'loading' || kind === 'processing' ? element('div', { className: 'spinner', 'aria-hidden': 'true' }) : null, element('span', { className: 'code', text: c[0] }), element('h2', { text: c[1] }), element('p', { text: c[2] }), element('div', { className: 'button-row', 'data-layout': 'center' }, element('button', { className: 'btn btn-primary', type: 'button', 'data-state-return': true, text: '返回正常状态' }), element('button', { className: 'btn', type: 'button', 'data-route': '/workbench', text: '返回工作台' })))); wireRoutes(layer); $('[data-state-return]', layer).addEventListener('click', () => showPageState('normal')); }
    let dialogReturnFocus = null;
    function openDialog(id) { const el = document.getElementById(id); if (!el)
        return; dialogReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null; el.hidden = false; document.body.classList.add('modal-open'); const f = $('button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled])', el); if (f)
        setTimeout(() => f.focus(), 0); }
    function closeDialog(el) { const root = typeof el === 'string' ? document.getElementById(el) : el.closest('.dialog-backdrop'); if (root)
        root.hidden = true; if (!$$('.dialog-backdrop').some(d => !d.hidden)) {
        document.body.classList.toggle('modal-open', false);
        const target = dialogReturnFocus;
        dialogReturnFocus = null;
        if (target && target.isConnected)
            setTimeout(() => target.focus(), 0);
    } }
    function wireRoutes(root = document) { $$('[data-route]', root).forEach(el => { if (el.dataset.routeBound)
        return; el.dataset.routeBound = '1'; el.addEventListener('click', e => { e.preventDefault(); const extra = {}; ['role', 'month', 'department', 'employee', 'batch', 'filter'].forEach(k => { if (el.dataset[k])
        extra[k] = el.dataset[k]; }); navigate(el.dataset.route, extra); }); }); }
    function wireUI() { wireRoutes(); $$('[data-open-dialog]').forEach(el => el.addEventListener('click', () => openDialog(el.dataset.openDialog))); $$('[data-close-dialog]').forEach(el => { if (!el.hasAttribute('type'))
        el.setAttribute('type', 'button'); if (!el.hasAttribute('aria-label') && el.textContent.trim() === '×')
        el.setAttribute('aria-label', '关闭'); el.addEventListener('click', () => closeDialog(el)); }); $$('.dialog-backdrop').forEach(d => d.addEventListener('mousedown', e => { if (e.target === d)
        closeDialog(d); })); $$('[data-tab-target]').forEach(tab => tab.addEventListener('click', () => { const group = tab.closest('[data-tabs]') || document; $$('[data-tab-target]', group).forEach(t => t.classList.toggle('active', t === tab)); $$('[data-tab-panel]', group).forEach(p => p.hidden = p.dataset.tabPanel !== tab.dataset.tabTarget); })); $$('.switch').forEach(sw => sw.addEventListener('click', () => sw.setAttribute('aria-checked', String(sw.getAttribute('aria-checked') !== 'true')))); $$('[data-prototype-unwired]').forEach(el => el.addEventListener('click', () => toast('原型未接入', el.dataset.prototypeUnwired || '该非主链路动作待工程实现。'))); $$('[data-state-patch]').forEach(el => el.addEventListener('click', () => { try {
        update(JSON.parse(el.dataset.statePatch), el.dataset.stateMessage || '关联页面已同步。');
    }
    catch {
        toast('原型状态错误', '无法应用当前模拟状态。');
    } })); }
    function navNodes() { const role = state.context.role, route = currentRoute(); return NAV.map(group => { const items = group.items.filter(item => item[3].split(',').includes(role)).map(item => { const root = item[1] === '/workbench' ? '/workbench' : '/' + item[1].split('/')[1]; const active = route === item[1] || route.startsWith(root + '/'); return element('button', { className: `nav-link${active ? ' active' : ''}`, type: 'button', 'data-route': item[1], 'data-od-id': `nav-${item[1].replace(/[^a-z0-9]+/gi, '-')}` }, icon(item[2]), element('span', { text: item[0] })); }); return items.length ? element('div', { className: 'nav-group' }, element('div', { className: 'nav-group-label', text: group.label }), items) : null; }).filter(Boolean); }
    function injectShell() {
        const main = $('.workspace');
        if (!main)
            return;
        const role = state.context.role;
        const skipLink = element('a', { className: 'skip-link', href: '#page-main', text: '跳到主要内容' });
        const topbar = element('header', { className: 'app-topbar', 'data-od-id': 'shared-global-topbar' },
            element('button', { className: 'btn btn-icon nav-toggle', id: 'shared-nav-toggle', type: 'button', 'aria-label': '打开主导航', 'aria-expanded': 'false' }, icon('menu')),
            element('div', { className: 'app-brand' }, element('img', { className: 'app-logo', src: 'assets/shenzhou-logo.svg', alt: '江苏神州半导体科技股份有限公司' })),
            element('form', { className: 'top-search', id: 'shared-search' }, icon('search'), element('label', { className: 'sr-only', for: 'shared-search-input', text: '全局搜索' }), element('input', { id: 'shared-search-input', type: 'search', placeholder: '搜索页面、制度或帮助' })),
            element('div', { className: 'topbar-spacer' }),
            element('div', { className: 'top-context' }, element('span', { className: 'fresh-dot' }), '数据更新至 14:18'),
            element('div', { className: 'top-context scope' }, icon('shield'), element('span', { id: 'shared-scope', text: ROLE_SCOPES[role] })),
            element('button', { className: 'avatar', id: 'shared-avatar', type: 'button', 'aria-label': '账号与角色', text: ROLE_LABELS[role].slice(0, 1) }));
        const sidebar = element('aside', { className: 'sidebar', id: 'shared-sidebar', 'data-od-id': 'shared-primary-navigation' }, navNodes(), element('div', { className: 'sidebar-note' }, element('strong', { text: 'V1.9 · PROJECT_EMBEDDED' }), '角色与数据范围必须由服务端重新校验。'));
        const scrim = element('div', { className: 'drawer-scrim', id: 'shared-scrim', hidden: true });
        document.body.prepend(skipLink, topbar, sidebar, scrim);
        const mobileItems = [[role === 'employee' ? '/me/today' : '/workbench', 'home', '今日'], [role === 'employee' ? '/me/records' : '/attendance/daily', 'file', '记录'], [role === 'employee' ? '/me/feedback' : '/attendance/exceptions', 'message', '反馈'], [role === 'employee' ? '/me/today' : '/workbench', 'user', '我的']];
        const mobileNav = element('nav', { className: 'mobile-nav', 'data-od-id': 'shared-mobile-navigation', 'aria-label': '移动端主导航' }, mobileItems.map(([route, iconName, label], index) => element('button', { className: index === 0 ? 'active' : '', type: 'button', 'data-route': route }, icon(iconName), element('span', { text: label }))));
        const launcherNode = element('button', { className: 'demo-launcher', id: 'v19-demo-launcher', type: 'button', 'aria-expanded': 'false', 'data-od-id': 'prototype-only-launcher', text: 'PROTOTYPE_ONLY · 演示场景' });
        const stateButtons = [['normal', '正常'], ['loading', '加载'], ['empty', '空数据'], ['failure', '失败'], ['forbidden', '403'], ['conflict', '冲突'], ['frozen', '冻结'], ['processing', '处理中'], ['partial', '部分成功'], ['success', '成功']].map(([key, label]) => element('button', { className: 'demo-button', type: 'button', 'data-demo-page-state': key, text: label }));
        const panelNode = element('aside', { className: 'demo-panel', id: 'v19-demo-panel', hidden: true, 'data-od-id': 'prototype-only-panel' },
            element('div', { className: 'demo-head' }, element('div', {}, element('strong', { text: '演示场景与重置' }), element('span', { text: 'PROTOTYPE_ONLY，不进入生产导航或权限实现。' })), element('button', { className: 'btn btn-icon btn-ghost', id: 'v19-demo-close', type: 'button', 'aria-label': '关闭' }, icon('x'))),
            element('div', { className: 'demo-group' }, element('label', { for: 'v19-demo-role', text: '演示角色' }), element('div', { id: 'v19-demo-role-slot' })),
            element('div', { className: 'demo-group' }, element('span', { className: 'field-label', text: '页面状态' }), element('div', { className: 'demo-grid' }, stateButtons)),
            element('div', { className: 'demo-group' }, element('button', { className: 'btn btn-danger', id: 'v19-reset-demo', type: 'button', 'data-size': 'full', text: '重置演示数据' })));
        document.body.append(mobileNav, launcherNode, panelNode);
        const roleSelect = document.createElement('select');
        roleSelect.className = 'demo-select';
        roleSelect.id = 'v19-demo-role';
        roleSelect.setAttribute('aria-label', '演示角色');
        for (const [value, label] of Object.entries(ROLE_LABELS)) {
            roleSelect.add(new Option(label, value, value === role, value === role));
        }
        $('#v19-demo-role-slot').replaceWith(roleSelect);
        const returnRoute = state.context.return, headActions = $('.page-head .head-actions');
        if (headActions && currentRoute() !== '/workbench' && returnRoute && ROUTES[returnRoute] && returnRoute !== currentRoute()) {
            const back = document.createElement('button');
            back.className = 'btn btn-ghost';
            back.type = 'button';
            back.dataset.odId = 'context-return-button';
            back.textContent = '返回来源页';
            back.addEventListener('click', () => navigate(returnRoute, { return: '/workbench' }));
            headActions.prepend(back);
        }
        $('#shared-nav-toggle').addEventListener('click', () => { const open = !$('#shared-sidebar').classList.contains('open'); $('#shared-sidebar').classList.toggle('open', open); $('#shared-scrim').hidden = !open; $('#shared-nav-toggle').setAttribute('aria-expanded', String(open)); });
        $('#shared-scrim').addEventListener('click', () => { $('#shared-sidebar').classList.toggle('open', false); $('#shared-scrim').hidden = true; });
        $('#shared-search').addEventListener('submit', e => { e.preventDefault(); toast('原型未接入', '全局搜索需要接入服务端索引后实施。'); });
        $('#shared-avatar').addEventListener('click', () => toast(ROLE_LABELS[role], `${ROLE_SCOPES[role]}；真实权限由服务端决定。`));
        const launcher = $('#v19-demo-launcher'), panel = $('#v19-demo-panel');
        launcher.addEventListener('click', () => { panel.hidden = !panel.hidden; launcher.setAttribute('aria-expanded', String(!panel.hidden)); });
        $('#v19-demo-close').addEventListener('click', () => { panel.hidden = true; launcher.setAttribute('aria-expanded', 'false'); launcher.focus(); });
        $('#v19-demo-role').addEventListener('change', e => { state.context.role = e.target.value; persist(); document.body.classList.add('preparing'); navigate('/workbench', { role: e.target.value, return: currentRoute() }); });
        $$('[data-demo-page-state]').forEach(b => b.addEventListener('click', () => { panel.hidden = true; launcher.setAttribute('aria-expanded', 'false'); showPageState(b.dataset.demoPageState); }));
        $('#v19-reset-demo').addEventListener('click', reset);
        wireRoutes();
    }
    function guard() { const allowed = (document.body.dataset.allowedRoles || 'management,hr,attendance,lead,employee,system,auditor').split(','); if (!allowed.includes(state.context.role)) {
        showPageState('forbidden');
        return false;
    } return true; }
    function element(tagName, attributes = {}, ...children) {
        const node = document.createElement(tagName);
        Object.entries(attributes).forEach(([name, value]) => {
            if (value === undefined || value === null || value === false)
                return;
            if (name === 'className')
                node.className = value;
            else if (name === 'text')
                node.textContent = value;
            else
                node.setAttribute(name, value === true ? '' : String(value));
        });
        children.flat(Infinity).forEach(child => {
            if (child === undefined || child === null || child === false)
                return;
            node.append(child instanceof Node ? child : document.createTextNode(String(child)));
        });
        return node;
    }
    function fragment(...children) {
        const node = document.createDocumentFragment();
        children.flat(Infinity).forEach(child => {
            if (child !== undefined && child !== null && child !== false)
                node.append(child instanceof Node ? child : document.createTextNode(String(child)));
        });
        return node;
    }
    function init() { setContextFromQuery(); injectShell(); renderBindings(); wireUI(); guard(); document.body.classList.toggle('preparing', false); document.dispatchEvent(new CustomEvent('v19:ready', { detail: state })); }
    document.addEventListener('keydown', e => { if (e.key === 'Escape') {
        const open = $$('.dialog-backdrop').find(d => !d.hidden);
        if (open)
            closeDialog(open);
        else if ($('#v19-demo-panel') && !$('#v19-demo-panel').hidden) {
            $('#v19-demo-panel').hidden = true;
            $('#v19-demo-launcher').setAttribute('aria-expanded', 'false');
        }
    } if (e.key === 'Tab') {
        const d = $$('.dialog-backdrop').find(x => !x.hidden);
        if (!d)
            return;
        const f = $$('button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),a[href]', d).filter(x => x.offsetParent !== null);
        if (!f.length)
            return;
        if (e.shiftKey && document.activeElement === f[0]) {
            e.preventDefault();
            f.at(-1).focus();
        }
        else if (!e.shiftKey && document.activeElement === f.at(-1)) {
            e.preventDefault();
            f[0].focus();
        }
    } });
    window.V19 = { get state() { return state; }, DEFAULT_STATE, ROLE_LABELS, ROLE_SCOPES, ROUTES, currentRoute, navigate, update, reset, get, renderBindings, toast, showPageState, openDialog, closeDialog, icon, element, fragment };
    if (document.readyState === 'loading')
        document.addEventListener('DOMContentLoaded', init);
    else
        init();
})();
