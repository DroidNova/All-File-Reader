'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const root = path.resolve(__dirname, '../../../..');
const asset = path.join(root, 'app/src/main/assets/excel_viewer/xlsx.full.min.js');
const fixtures = path.join(root, 'app/src/test/resources/xls');
const XLSX = require(asset);
assert.strictEqual(XLSX.version, '0.20.3');
const expectedHashes = {
  'minimal-biff8.xls': '4fabc7b9d1bf34a4dbfea3e5e6b2826efb1d352a0a45b241ca9b4e22c5339a5d',
  'multi-sheet-biff8.xls': 'c71e87832ea2739eab1f93c14aac481b826e2f4fef0f2dbabfebee139b8149de',
  'features-uppercase.XLS': '66563ce914a958bdbe81d6e278ae660ac3db359146182fc205fe12330c768f08',
  'empty-sheet-biff8.xls': '08b65d4e6902b457be014ffd8c731d45f3b9bfdba161d0b59ea42162211a89bd',
  'minimal-biff5.xls': '4892979c1de6afd86acf74ed1765545da503c354da84de0cf354cdb44b405b71',
};
function bytes(name) {
  const data = Buffer.from(fs.readFileSync(path.join(fixtures, name + '.b64'), 'ascii').replace(/\s/g, ''), 'base64');
  assert.strictEqual(crypto.createHash('sha256').update(data).digest('hex'), expectedHashes[name]);
  return data;
}
function read(name) {
  return XLSX.read(bytes(name), { type: 'buffer', cellFormula: true, cellText: true, cellDates: true, bookVBA: false, WTF: false });
}
let book = read('minimal-biff8.xls');
assert.deepStrictEqual(book.SheetNames, ['Sheet1']);
assert.strictEqual(book.Sheets.Sheet1.A1.v, 'Hello XLS');
assert.strictEqual(book.Sheets.Sheet1.A1.t, 's');
book = read('multi-sheet-biff8.xls');
assert.deepStrictEqual(book.SheetNames, ['First', 'Second']);
assert.strictEqual(book.Sheets.First.B1.v, 1);
assert.strictEqual(book.Sheets.Second.A1.v, 'two');
book = read('features-uppercase.XLS');
assert.deepStrictEqual(book.SheetNames, ['Features']);
const sheet = book.Sheets.Features;
assert.strictEqual(sheet.A2.v, 'alpha'); assert.strictEqual(sheet.A2.t, 's');
assert.strictEqual(sheet.B2.v, 2); assert.strictEqual(sheet.B2.t, 'n');
assert.strictEqual(sheet.C2.v, true); assert.strictEqual(sheet.C2.t, 'b');
assert.strictEqual(sheet.D2.w, '2020-01-02');
assert.strictEqual(sheet.E2.f, '$B$2+3'); assert.strictEqual(sheet.E2.v, 5);
book = read('minimal-biff5.xls');
assert.deepStrictEqual(book.SheetNames, ['BIFF5']);
assert.strictEqual(book.Sheets.BIFF5.B1.v, 5);
book = read('empty-sheet-biff8.xls');
assert.deepStrictEqual(book.SheetNames, ['Empty']);
assert.strictEqual(book.Sheets.Empty['!ref'], 'A1');
assert.strictEqual(book.Sheets.Empty.A1, undefined);
console.log('Bundled SheetJS 0.20.3 XLS contract: PASS (5 fixtures)');
