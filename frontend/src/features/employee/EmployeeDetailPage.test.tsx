import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import dayjs from 'dayjs';

import '../../shared/i18n/i18n';
import { EmployeeDetailPage, employeeOrganizationOptions, toPeriodRequest } from './EmployeeDetailPage';

const employeeApi = vi.hoisted(() => ({
  getEmployee: vi.fn(),
  listEmploymentPeriods: vi.fn(),
  listPriorServiceRecords: vi.fn(),
  getAnnualLeaveAccount: vi.fn(),
  getTimeOffAccount: vi.fn(),
  getPunchExemption: vi.fn(),
  updateLocalEmployee: vi.fn(),
  createPriorServiceAdjustment: vi.fn(),
}));

const organizationApi = vi.hoisted(() => ({
  getCurrentOrganizationTree: vi.fn(),
}));

vi.mock('./employeeApi', () => employeeApi);
vi.mock('../organization/organizationApi', () => organizationApi);
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return {
    ...actual,
    useParams: () => ({ employeeId: 'test-employee-id' }),
  };
});

function renderWithRouter(component: React.ReactElement) {
  return render(
    <MemoryRouter>
      {component}
    </MemoryRouter>
  );
}

describe('EmployeeDetailPage employee status edit', () => {
  beforeEach(() => {
    employeeApi.getEmployee.mockResolvedValue({
      employeeId: 'test-employee-id',
      employeeNumber: 'E001',
      displayName: '测试员工',
      status: 'ACTIVE',
      effectiveFrom: '2024-01-01',
      effectiveTo: null,
      rowVersion: 1,
    });
    employeeApi.listEmploymentPeriods.mockResolvedValue({ items: [] });
    employeeApi.listPriorServiceRecords.mockResolvedValue({ items: [], totalDays: 0 });
    organizationApi.getCurrentOrganizationTree.mockResolvedValue([]);
    employeeApi.getPunchExemption.mockResolvedValue({ standingExempt: false });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('highlights the current status button correctly using Form.useWatch', async () => {
    renderWithRouter(<EmployeeDetailPage capabilities={['EMPLOYEE:EDIT', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());
    
    fireEvent.click(screen.getByRole('button', { name: '编辑员工' }));

    await waitFor(() => {
      const activeButton = screen.getByRole('button', { name: '在职' });
      expect(activeButton).toHaveClass('ant-btn-primary');
    });
  });

  it('updates button highlight immediately when status changes', async () => {
    renderWithRouter(<EmployeeDetailPage capabilities={['EMPLOYEE:EDIT', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());
    
    fireEvent.click(screen.getByRole('button', { name: '编辑员工' }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '在职' })).toHaveClass('ant-btn-primary');
    });

    fireEvent.click(screen.getByRole('button', { name: '离职' }));

    await waitFor(() => {
      const terminatedButton = screen.getByRole('button', { name: '离职' });
      expect(terminatedButton).toHaveClass('ant-btn-primary');
      const activeButton = screen.getByRole('button', { name: '在职' });
      expect(activeButton).not.toHaveClass('ant-btn-primary');
    });
  });

  it('shows warning and clears date when status changes from original', async () => {
    renderWithRouter(<EmployeeDetailPage capabilities={['EMPLOYEE:EDIT', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());
    
    fireEvent.click(screen.getByRole('button', { name: '编辑员工' }));

    await waitFor(() => expect(screen.getByRole('button', { name: '离职' })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '离职' }));

    await waitFor(() => {
      expect(screen.getByText('状态变更需明确生效日期')).toBeInTheDocument();
      expect(screen.getByText(/请明确选择生效日期/)).toBeInTheDocument();
    });

    // Date should be cleared, not set to today
    const datePicker = screen.getAllByPlaceholderText('Select date')[0];
    expect(datePicker).toHaveValue('');
  });

  it('requires user to select date when status changes (cannot submit without date)', async () => {
    renderWithRouter(<EmployeeDetailPage capabilities={['EMPLOYEE:EDIT', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '编辑员工' }));

    await waitFor(() => expect(screen.getByRole('button', { name: '离职' })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '离职' }));

    // Try to submit without selecting date
    fireEvent.click(screen.getByRole('button', { name: '确 认' }));

    // Should show required field error
    await waitFor(() => {
      const errorElement = screen.queryByText(/'effectiveFrom' is required/);
      expect(errorElement).toBeInTheDocument();
    });
  });

  it('restores original effectiveFrom when switching back to original status', async () => {
    renderWithRouter(<EmployeeDetailPage capabilities={['EMPLOYEE:EDIT', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());
    
    fireEvent.click(screen.getByRole('button', { name: '编辑员工' }));

    await waitFor(() => expect(screen.getByRole('button', { name: '离职' })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '离职' }));
    await waitFor(() => expect(screen.getByText('状态变更需明确生效日期')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '在职' }));

    const datePicker = screen.getAllByPlaceholderText('Select date')[0];
    await waitFor(() => {
      expect(datePicker).toHaveValue('2024-01-01');
    });

    expect(screen.queryByText('状态变更需明确生效日期')).not.toBeInTheDocument();
  });

  it('validates that new effectiveFrom must be after original when status changes', async () => {
    employeeApi.updateLocalEmployee.mockRejectedValue(new Error('Should not call API'));

    renderWithRouter(<EmployeeDetailPage capabilities={['EMPLOYEE:EDIT', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '编辑员工' }));

    await waitFor(() => expect(screen.getByRole('button', { name: '离职' })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '离职' }));

    // Wait for warning alert to appear
    await waitFor(() => {
      expect(screen.getByText('状态变更需明确生效日期')).toBeInTheDocument();
    });

    // Fill in reason
    const reasonInput = screen.getByLabelText(/原因/);
    fireEvent.change(reasonInput, { target: { value: '测试原因' } });

    // Select same date as original - should trigger validation error
    const datePickers = screen.getAllByPlaceholderText('Select date');
    const effectiveFromPicker = datePickers[0];
    if (!effectiveFromPicker) throw new Error('effectiveFrom picker not found');
    fireEvent.change(effectiveFromPicker, { target: { value: '2024-01-01' } });

    // Try to submit - validation should prevent it
    fireEvent.click(screen.getByRole('button', { name: '确 认' }));

    await waitFor(() => {
      expect(screen.getByText('新状态生效日期必须严格晚于原生效日期')).toBeInTheDocument();
    });

    // Verify API was not called
    expect(employeeApi.updateLocalEmployee).not.toHaveBeenCalled();
  });

  it('preserves effectiveTo as empty when changing status to TERMINATED', async () => {
    renderWithRouter(<EmployeeDetailPage capabilities={['EMPLOYEE:EDIT', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());
    
    fireEvent.click(screen.getByRole('button', { name: '编辑员工' }));

    await waitFor(() => expect(screen.getByRole('button', { name: '离职' })).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '离职' }));

    const datePickers = screen.getAllByPlaceholderText('Select date');
    const effectiveToPicker = datePickers[1];
    
    expect(effectiveToPicker).toHaveValue('');
  });
});

describe('EmployeeDetailPage prior service years precision', () => {
  beforeEach(() => {
    employeeApi.getEmployee.mockResolvedValue({
      employeeId: 'test-employee-id',
      employeeNumber: 'E001',
      displayName: '测试员工',
      status: 'ACTIVE',
      effectiveFrom: '2024-01-01',
      effectiveTo: null,
      rowVersion: 1,
    });
    employeeApi.listEmploymentPeriods.mockResolvedValue({ items: [] });
    employeeApi.listPriorServiceRecords.mockResolvedValue({ items: [], totalDays: 0 });
    organizationApi.getCurrentOrganizationTree.mockResolvedValue([]);
    employeeApi.getPunchExemption.mockResolvedValue({ standingExempt: false });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('accepts 2 decimal places for years input', async () => {
    renderWithRouter(<EmployeeDetailPage capabilities={['PRIOR_SERVICE:ADJUST', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());

    fireEvent.click(screen.getByText('新增调整'));

    await waitFor(() => expect(screen.getByText(/输入工龄调整年数/)).toBeInTheDocument());

    const yearInput = screen.getByRole('spinbutton');

    fireEvent.change(yearInput, { target: { value: '1.5000' } });

    await waitFor(() => {
      expect(yearInput).toHaveValue('1.50');
    });
  });

  it('shows preview of years to days conversion', async () => {
    renderWithRouter(<EmployeeDetailPage capabilities={['PRIOR_SERVICE:ADJUST', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());

    fireEvent.click(screen.getByText('新增调整'));

    await waitFor(() => expect(screen.getByText(/输入工龄调整年数/)).toBeInTheDocument());

    const yearInput = screen.getByRole('spinbutton');

    fireEvent.change(yearInput, { target: { value: '1.5' } });

    await waitFor(() => {
      expect(screen.getByText(/1\.50 年 = 548 天/)).toBeInTheDocument();
    });
  });

  it('calculates small adjustments correctly (1 day ≈ 0.01 years)', async () => {
    renderWithRouter(<EmployeeDetailPage capabilities={['PRIOR_SERVICE:ADJUST', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());
    
    fireEvent.click(screen.getByText('新增调整'));

    await waitFor(() => expect(screen.getByText(/输入工龄调整年数/)).toBeInTheDocument());

    const yearInput = screen.getByRole('spinbutton');
    
    fireEvent.change(yearInput, { target: { value: '0.01' } });
    
    await waitFor(() => {
      expect(screen.getByText(/0\.01 年 = 4 天/)).toBeInTheDocument();
    });
  });

  it('rejects zero as invalid adjustment', async () => {
    renderWithRouter(<EmployeeDetailPage capabilities={['PRIOR_SERVICE:ADJUST', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());

    fireEvent.click(screen.getByText('新增调整'));

    await waitFor(() => expect(screen.getByText(/输入工龄调整年数/)).toBeInTheDocument());

    const yearInput = screen.getByRole('spinbutton');
    const reasonInput = screen.getByLabelText(/原因/);

    fireEvent.change(yearInput, { target: { value: '0' } });
    fireEvent.change(reasonInput, { target: { value: '测试原因' } });

    fireEvent.click(screen.getByRole('button', { name: '确 认' }));

    await waitFor(() => {
      expect(screen.getByText(/调整量无效：不能为 0 年或折合 0 天/)).toBeInTheDocument();
    });
  });

  it('rejects value that rounds to zero days', async () => {
    renderWithRouter(<EmployeeDetailPage capabilities={['PRIOR_SERVICE:ADJUST', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());

    fireEvent.click(screen.getByText('新增调整'));

    await waitFor(() => expect(screen.getByText(/输入工龄调整年数/)).toBeInTheDocument());

    const yearInput = screen.getByRole('spinbutton');
    const reasonInput = screen.getByLabelText(/原因/);

    fireEvent.change(yearInput, { target: { value: '0.0001' } });
    fireEvent.change(reasonInput, { target: { value: '测试原因' } });

    fireEvent.click(screen.getByRole('button', { name: '确 认' }));

    await waitFor(() => {
      expect(screen.getByText(/调整量无效：不能为 0 年或折合 0 天/)).toBeInTheDocument();
    });
  });

  it('respects boundary limits (-100.00 to 100.00 years)', async () => {
    renderWithRouter(<EmployeeDetailPage capabilities={['PRIOR_SERVICE:ADJUST', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());

    fireEvent.click(screen.getByText('新增调整'));

    await waitFor(() => expect(screen.getByText(/输入工龄调整年数/)).toBeInTheDocument());

    const yearInput = screen.getByRole('spinbutton');

    fireEvent.change(yearInput, { target: { value: '100.00' } });

    await waitFor(() => {
      expect(screen.getByText(/100\.00 年 = 36500 天/)).toBeInTheDocument();
    });
  });

  it('handles negative adjustments correctly', async () => {
    renderWithRouter(<EmployeeDetailPage capabilities={['PRIOR_SERVICE:ADJUST', 'EMPLOYMENT:READ', 'PRIOR_SERVICE:READ']} />);

    await waitFor(() => expect(screen.getByRole('heading', { name: '测试员工' })).toBeInTheDocument());

    fireEvent.click(screen.getByText('新增调整'));

    await waitFor(() => expect(screen.getByText(/输入工龄调整年数/)).toBeInTheDocument());

    const yearInput = screen.getByRole('spinbutton');

    fireEvent.change(yearInput, { target: { value: '-0.5' } });

    await waitFor(() => {
      expect(screen.getByText(/-0\.50 年 = -183 天/)).toBeInTheDocument();
    });
  });
});

describe('toPeriodRequest', () => {
  it('converts form values to period request correctly', () => {
    const formValues = {
      organizationId: 'org-123',
      positionId: 'pos-456',
      startDate: dayjs('2024-01-01'),
      terminationDate: dayjs('2024-12-31'),
      reason: '  测试原因  ',
    };

    const result = toPeriodRequest(formValues);

    expect(result).toEqual({
      organizationId: 'org-123',
      positionId: 'pos-456',
      startDate: '2024-01-01',
      terminationDate: '2024-12-31',
      reason: '测试原因',
    });
  });

  it('handles null terminationDate', () => {
    const formValues = {
      organizationId: 'org-123',
      positionId: undefined,
      startDate: dayjs('2024-01-01'),
      terminationDate: null,
      reason: '测试',
    };

    const result = toPeriodRequest(formValues);

    expect(result.terminationDate).toBeNull();
    expect(result.positionId).toBeNull();
  });
});

describe('employeeOrganizationOptions', () => {
  it('filters out COMPANY type organizations unless already selected', () => {
    const nodes = [
      {
        organizationId: 'company-1',
        name: '公司',
        code: 'COMP',
        organizationType: 'COMPANY' as const,
        status: 'ACTIVE' as const,
        sourceOrganizationId: 'src-1',
        effectiveFrom: '2024-01-01',
        effectiveTo: null,
        children: [],
      },
      {
        organizationId: 'dept-1',
        name: '部门',
        code: 'DEPT',
        organizationType: 'DEPARTMENT' as const,
        status: 'ACTIVE' as const,
        sourceOrganizationId: 'src-2',
        effectiveFrom: '2024-01-01',
        effectiveTo: null,
        children: [],
      },
    ];

    const options = employeeOrganizationOptions(nodes, []);

    expect(options).toHaveLength(1);
    expect(options[0]?.value).toBe('dept-1');
  });

  it('includes COMPANY when it is in selected list', () => {
    const nodes = [
      {
        organizationId: 'company-1',
        name: '公司',
        code: 'COMP',
        organizationType: 'COMPANY' as const,
        status: 'ACTIVE' as const,
        sourceOrganizationId: 'src-1',
        effectiveFrom: '2024-01-01',
        effectiveTo: null,
        children: [],
      },
    ];

    const options = employeeOrganizationOptions(nodes, ['company-1']);

    expect(options).toHaveLength(1);
    expect(options[0]?.value).toBe('company-1');
    expect(options[0]?.disabled).toBe(true);
  });
});
