import ExcelJS from 'exceljs';

import { triggerBrowserDownload } from '../../shared/api/apiClient';
import { excelWrappedDepartment } from './departmentPath';

const WHITE = 'FFFFFFFF';
const HEADER = 'FFDCEBF2';
const INK = 'FF24344D';

export async function downloadQuerySheetWorkbook(request: {
  title: string;
  month: string;
  headers: string[];
  rows: Array<Array<string>>;
}): Promise<void> {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet(request.title.slice(0, 31), {
    views: [{ showGridLines: true, state: 'frozen', ySplit: 1 }],
  });
  const header = sheet.addRow(request.headers);
  header.eachCell((cell) => {
    cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: HEADER } };
    cell.font = { bold: true, color: { argb: INK } };
    cell.border = {
      top: { style: 'thin', color: { argb: 'FFD0D7DE' } },
      left: { style: 'thin', color: { argb: 'FFD0D7DE' } },
      bottom: { style: 'thin', color: { argb: 'FFD0D7DE' } },
      right: { style: 'thin', color: { argb: 'FFD0D7DE' } },
    };
  });
  const departmentIndex = request.headers.indexOf('部门');
  if (request.rows.length === 0) {
    sheet.addRow(['当前筛选条件下没有记录']);
  }
  request.rows.forEach((values) => {
    const excelRow = sheet.addRow(values.map((value, column) => (
      column === departmentIndex ? excelWrappedDepartment(value) : value
    )));
    excelRow.eachCell((cell, columnNumber) => {
      const raw = values[columnNumber - 1];
      if (typeof raw === 'string' && raw.startsWith('=')) {
        cell.value = { formula: raw.slice(1) };
      } else if (typeof raw === 'string' && raw !== '' && /^-?\d+(\.\d+)?$/.test(raw)) {
        cell.value = Number(raw);
      }
      cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: WHITE } };
      cell.font = { color: { argb: INK } };
      if (departmentIndex >= 0 && columnNumber === departmentIndex + 1) {
        cell.alignment = { wrapText: true, vertical: 'middle', horizontal: 'left' };
      }
    });
  });
  const buffer = await workbook.xlsx.writeBuffer();
  const blob = new Blob([buffer], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  triggerBrowserDownload(blob, `${request.month}_${request.title}.xlsx`);
}
