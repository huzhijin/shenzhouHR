import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../shared/i18n/i18n';
import { ApiRequestError } from '../../shared/api/apiClient';
import * as organizationApi from '../organization/organizationApi';
import type { OrganizationNode } from '../organization/organizationApi';
import * as employeeApi from './employeeApi';
import { EmployeesPage } from './EmployeesPage';

const organizations: OrganizationNode[] = [{
  organizationId: 'company-organization',
  code: 'GROUP',
  name: '集团总部',
  organizationType: 'COMPANY',
  status: 'ACTIVE',
  sourceOrganizationId: null,
  effectiveFrom: '2026-01-01T00:00:00Z',
  effectiveTo: null,
  children: [{
    organizationId: 'finance-department',
    code: 'FIN',
    name: '财务部',
    organizationType: 'DEPARTMENT',
    status: 'ACTIVE',
    sourceOrganizationId: null,
    effectiveFrom: '2026-01-01T00:00:00Z',
    effectiveTo: null,
    children: [{
      organizationId: 'payroll-team',
      code: 'PAY',
      name: '薪酬组',
      organizationType: 'TEAM',
      status: 'ACTIVE',
      sourceOrganizationId: null,
      effectiveFrom: '2026-01-01T00:00:00Z',
      effectiveTo: null,
      children: [],
    }],
  }],
}];

describe('EmployeesPage organization directory', () => {
  beforeEach(() => {
    vi.spyOn(organizationApi, 'getCurrentOrganizationTree')
      .mockResolvedValue(organizations);
    vi.spyOn(employeeApi, 'getEmployees').mockResolvedValue({
      items: [{
        employeeId: 'employee-1',
        employeeVersionId: 'employee-version-1',
        employeeNumber: 'SZJN0001',
        displayName: '李雪发',
        employmentStatus: 'ACTIVE',
        organizationId: 'payroll-team',
        organizationName: '薪酬组',
        organizationCode: 'PAY',
        seeyonOaCode: null,
        bindingStatus: null,
        assignmentEffectiveFrom: '2026-01-01T00:00:00Z',
        assignmentEffectiveTo: null,
        sourceAuthority: 'INITIAL_EXCEL',
        rowVersion: 1,
      }],
      total: 1,
      page: 0,
      size: 20,
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('loads all authorized people by default and expands the complete organization tree', async () => {
    const view = renderEmployeesPage();

    const tree = await within(view.container).findByRole('tree', { name: '公司与部门' });
    expect(within(tree).getByText('全部人员')).toBeInTheDocument();
    expect(within(tree).getByText('集团总部')).toBeInTheDocument();
    expect(within(tree).getByText('财务部')).toBeInTheDocument();
    expect(within(tree).getByText('薪酬组')).toBeInTheDocument();
    expect((await screen.findAllByText('李雪发')).length).toBeGreaterThan(0);

    expect(employeeApi.getEmployees).toHaveBeenCalledWith(
      0,
      20,
      expect.objectContaining({
        organizationId: undefined,
        includeDescendants: false,
      }),
    );
  });

  it('filters by the selected company or department and includes every descendant', async () => {
    renderEmployeesPage();

    const tree = await screen.findByRole('tree', { name: '公司与部门' });
    fireEvent.click(within(tree).getByText('财务部'));

    await waitFor(() => {
      expect(employeeApi.getEmployees).toHaveBeenLastCalledWith(
        0,
        20,
        expect.objectContaining({
          organizationId: 'finance-department',
          includeDescendants: true,
        }),
      );
    });
    expect(screen.getByRole('heading', { name: '财务部' })).toBeInTheDocument();
    expect(screen.getByText('显示当前节点及全部下级组织的员工。'))
      .toBeInTheDocument();
  });

  it('keeps a late stale response from replacing the latest organization result', async () => {
    const initialRequest = deferred<employeeApi.EmployeePage>();
    const organizationRequest = deferred<employeeApi.EmployeePage>();
    vi.mocked(employeeApi.getEmployees)
      .mockReset()
      .mockImplementationOnce(() => initialRequest.promise)
      .mockImplementationOnce(() => organizationRequest.promise);
    const view = renderEmployeesPage();

    const tree = await within(view.container).findByRole('tree', { name: '公司与部门' });
    fireEvent.click(within(tree).getByText('财务部'));
    await waitFor(() => expect(employeeApi.getEmployees).toHaveBeenCalledTimes(2));

    await act(async () => {
      organizationRequest.resolve(employeePage('latest-employee', '最新部门员工'));
    });
    expect((await within(view.container).findAllByText('最新部门员工')).length)
      .toBeGreaterThan(0);

    await act(async () => {
      initialRequest.resolve(employeePage('stale-employee', '过期全部员工'));
    });
    expect(within(view.container).getAllByText('最新部门员工').length)
      .toBeGreaterThan(0);
    expect(within(view.container).queryByText('过期全部员工')).not.toBeInTheDocument();
  });

  it('keeps a late stale directory response from replacing the latest tree', async () => {
    const initialDirectoryRequest = deferred<OrganizationNode[]>();
    const refreshDirectoryRequest = deferred<OrganizationNode[]>();
    vi.mocked(organizationApi.getCurrentOrganizationTree)
      .mockReset()
      .mockImplementationOnce(() => initialDirectoryRequest.promise)
      .mockImplementationOnce(() => refreshDirectoryRequest.promise);
    const view = renderEmployeesPage();

    fireEvent.click(within(view.container).getByRole('button', { name: '刷新' }));
    await waitFor(() => {
      expect(organizationApi.getCurrentOrganizationTree).toHaveBeenCalledTimes(2);
    });

    await act(async () => {
      refreshDirectoryRequest.resolve([organizationNode(
        'latest-company',
        '最新组织树',
      )]);
    });
    expect(await within(view.container).findByText('最新组织树')).toBeInTheDocument();

    await act(async () => {
      initialDirectoryRequest.resolve([organizationNode(
        'stale-company',
        '过期组织树',
      )]);
    });
    expect(within(view.container).getByText('最新组织树')).toBeInTheDocument();
    expect(within(view.container).queryByText('过期组织树')).not.toBeInTheDocument();
  });

  it('preserves the selected tree and scope when a directory refresh fails', async () => {
    const view = renderEmployeesPage();
    const tree = await within(view.container).findByRole('tree', { name: '公司与部门' });
    fireEvent.click(within(tree).getByText('财务部'));
    await waitFor(() => {
      expect(employeeApi.getEmployees).toHaveBeenLastCalledWith(
        0,
        20,
        expect.objectContaining({ organizationId: 'finance-department' }),
      );
    });
    vi.mocked(organizationApi.getCurrentOrganizationTree).mockRejectedValueOnce(
      new ApiRequestError(0, {
        code: 'ORGANIZATION_UNAVAILABLE',
        message: '目录刷新失败',
        retryable: true,
      }),
    );

    fireEvent.click(within(view.container).getByRole('button', { name: '刷新' }));

    expect(await within(view.container).findByText('目录刷新失败')).toBeInTheDocument();
    expect(within(view.container).getByText('集团总部')).toBeInTheDocument();
    expect(within(tree).getByText('财务部')).toBeInTheDocument();
    expect(within(view.container).getByRole('heading', { name: '财务部' }))
      .toBeInTheDocument();
    expect(employeeApi.getEmployees).toHaveBeenLastCalledWith(
      0,
      20,
      expect.objectContaining({
        organizationId: 'finance-department',
        includeDescendants: true,
      }),
    );
  });
});

function renderEmployeesPage() {
  return render(
    <MemoryRouter>
      <EmployeesPage capabilities={[]} />
    </MemoryRouter>,
  );
}

function employeePage(employeeId: string, displayName: string): employeeApi.EmployeePage {
  return {
    items: [{
      employeeId,
      employeeVersionId: `${employeeId}-version`,
      employeeNumber: employeeId,
      displayName,
      employmentStatus: 'ACTIVE',
      organizationId: 'finance-department',
      organizationName: '财务部',
      organizationCode: 'FIN',
      seeyonOaCode: null,
      bindingStatus: null,
      assignmentEffectiveFrom: '2026-01-01T00:00:00Z',
      assignmentEffectiveTo: null,
      sourceAuthority: 'INITIAL_EXCEL',
      rowVersion: 1,
    }],
    total: 1,
    page: 0,
    size: 20,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((nextResolve) => {
    resolve = nextResolve;
  });
  return { promise, resolve };
}

function organizationNode(
  organizationId: string,
  name: string,
): OrganizationNode {
  return {
    organizationId,
    code: organizationId,
    name,
    organizationType: 'COMPANY',
    status: 'ACTIVE',
    sourceOrganizationId: null,
    effectiveFrom: '2026-01-01T00:00:00Z',
    effectiveTo: null,
    children: [],
  };
}
