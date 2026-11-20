import { test } from 'node:test'
import assert from 'node:assert/strict'
import { safeSegment, safeZipPath } from './naming.ts'

test('safeSegment matches the backend rules', () => {
  assert.equal(safeSegment('1 Structure'), '1 Structure')
  assert.equal(safeSegment('a:b?c'), 'a_b_c')
  assert.equal(safeSegment('CON'), '_CON')
  assert.equal(safeSegment('Trailing dot. '), 'Trailing dot')
  assert.equal(safeSegment('Form[N1]{x}.docx'), 'Form[N1]{x}.docx')
  assert.equal(safeSegment('   '), '_')
})

test('safeZipPath keeps structure and blocks zip slip', () => {
  assert.equal(safeZipPath('Demo site/1 Structure/a.docx'), 'Demo site/1 Structure/a.docx')
  assert.equal(safeZipPath('folder\\sub\\b?.pdf'), 'folder/sub/b_.pdf')
  assert.equal(safeZipPath('./x/./y.txt'), 'x/y.txt')
  assert.equal(safeZipPath('../evil.txt'), null)
  assert.equal(safeZipPath('a/../../evil.txt'), null)
  assert.equal(safeZipPath('/etc/passwd'), null)
  assert.equal(safeZipPath('C:/Windows/x.dll'), null)
  assert.equal(safeZipPath(''), null)
})
