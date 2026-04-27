import { chromium } from 'playwright';

const input = 'file:///Users/prashantpandey/Downloads/demo/docs/PROJECT_OWNER_VISUAL_DIAGRAMS_RENDERED.html';
const output = '/Users/prashantpandey/Downloads/demo/docs/PROJECT_OWNER_VISUAL_DIAGRAMS_VISUAL.pdf';

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
await page.goto(input, { waitUntil: 'networkidle' });
await page.pdf({
  path: output,
  printBackground: true,
  format: 'A4',
  landscape: true,
  margin: { top: '10mm', right: '10mm', bottom: '10mm', left: '10mm' }
});
await browser.close();
console.log(output);
