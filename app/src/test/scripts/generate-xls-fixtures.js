'use strict';
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '../../../..');
const XLSX = require(path.join(root, 'app/src/main/assets/excel_viewer/xlsx.full.min.js'));
const output = path.join(root, 'app/src/test/resources/xls');
function workbook(sheets) {
  const result = XLSX.utils.book_new();
  for (const [name, sheet] of sheets) XLSX.utils.book_append_sheet(result, sheet, name);
  return result;
}
function write(name, book, bookType = 'xls') {
  const bytes = XLSX.write(book, { type: 'buffer', bookType, bookSST: false });
  fs.writeFileSync(path.join(output, name + '.b64'), wrapBase64(bytes));
}
function wrapBase64(bytes) { return bytes.toString('base64').match(/.{1,76}/g).join('\n') + '\n'; }
write('minimal-biff8.xls', workbook([['Sheet1', XLSX.utils.aoa_to_sheet([['Hello XLS']])]]));
write('multi-sheet-biff8.xls', workbook([
  ['First', XLSX.utils.aoa_to_sheet([['one', 1]])],
  ['Second', XLSX.utils.aoa_to_sheet([['two', 2]])],
]));
const features = XLSX.utils.aoa_to_sheet([
  ['Text', 'Number', 'Boolean', 'Date', 'Formula'],
  ['alpha', 2, true, 43832, null],
]);
features.D2.z = 'yyyy-mm-dd';
features.E2 = { t: 'n', v: 5, w: '5' };
features['!ref'] = 'A1:E2';
write('features-uppercase.XLS', workbook([['Features', features]]));
// SheetJS CE writes cached values but does not emit BIFF formula records. Insert one
// deterministic FORMULA record into the synthetic workbook stream, then let the
// same bundled CFB writer rebuild sector chains and directory sizes.
{
  const file = path.join(output, 'features-uppercase.XLS.b64');
  const cfb = XLSX.CFB.read(Buffer.from(fs.readFileSync(file, 'ascii').replace(/\s/g, ''), 'base64'), { type: 'buffer' });
  const entry = cfb.FileIndex.find(item => item.name === 'Workbook');
  const source = Buffer.from(entry.content);
  let record = -1;
  for (let offset = 0; offset + 18 <= source.length;) {
    const id = source.readUInt16LE(offset), length = source.readUInt16LE(offset + 2);
    if (id === 0x0203 && length === 14 && source.readUInt16LE(offset + 4) === 1 && source.readUInt16LE(offset + 6) === 4) { record = offset; break; }
    offset += 4 + length;
  }
  if (record < 0) throw new Error('cached formula cell record not found');
  const payload = Buffer.alloc(31);
  payload.writeUInt16LE(1, 0); payload.writeUInt16LE(4, 2); payload.writeUInt16LE(0, 4);
  payload.writeDoubleLE(5, 6); payload.writeUInt16LE(0, 14); payload.writeUInt32LE(0, 16);
  payload.writeUInt16LE(9, 20); payload[22] = 0x44; payload.writeUInt16LE(1, 23);
  payload.writeUInt16LE(1, 25); payload[27] = 0x1e; payload.writeUInt16LE(3, 28); payload[30] = 0x03;
  const header = Buffer.alloc(4); header.writeUInt16LE(0x0006, 0); header.writeUInt16LE(payload.length, 2);
  entry.content = Buffer.concat([source.subarray(0, record), header, payload, source.subarray(record + 18)]);
  entry.size = entry.content.length;
  fs.writeFileSync(file, wrapBase64(XLSX.CFB.write(cfb, { type: 'buffer' })));
}
write('empty-sheet-biff8.xls', workbook([['Empty', XLSX.utils.aoa_to_sheet([])]]));
write('minimal-biff5.xls', workbook([['BIFF5', XLSX.utils.aoa_to_sheet([['old', 5]])]]), 'biff5');
