'use strict';
// Optional real-browser smoke test. Start tests/editor-test-server.sh first.
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const {chromium}=require(process.env.TABPREFIX_PLAYWRIGHT_MODULE||'playwright');
const project=path.resolve(process.argv[2]||'.');
let info;
const output=path.join(project,'.build/browser-checks');
fs.mkdirSync(output,{recursive:true});
let checks=0;
const check=(condition,name)=>{assert.ok(condition,name);checks++;};
(async()=>{
 let server;
 if(process.argv.includes('--start-server')){
  const {spawn}=require('node:child_process');
  const provided=fs.readdirSync(path.join(project,'.build/deps/provided')).filter(n=>n.endsWith('.jar')).map(n=>path.join(project,'.build/deps/provided',n));
  const cp=[path.join(project,'.build/tests.jar'),path.join(project,'dist/TabPrefix.jar'),...provided].join(path.delimiter);
  server=spawn(process.env.TABPREFIX_JAVA||'java',['-Djava.awt.headless=true','-cp',cp,'me.snowsun.tabprefix.test.FullIntegrationTest',project,'--serve'],{stdio:['pipe','pipe','inherit']});
  await new Promise((resolve,reject)=>{const timeout=setTimeout(()=>reject(new Error('Editor fixture startup timed out')),15000);server.stdout.on('data',data=>{if(data.toString().includes('EDITOR_TEST_READY')){clearTimeout(timeout);resolve();}});server.once('exit',code=>{clearTimeout(timeout);reject(new Error('Editor fixture exited: '+code));});});
 }
 info=JSON.parse(fs.readFileSync(path.join(project,'.build/editor-test.json'),'utf8'));
 const browser=await chromium.launch({headless:true,executablePath:process.env.TABPREFIX_BROWSER_EXECUTABLE||undefined,args:['--no-sandbox','--disable-dev-shm-usage','--disable-gpu']});
 const context=await browser.newContext({viewport:{width:1320,height:1050},deviceScaleFactor:1});
 const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const idle=async()=>{await page.waitForFunction(()=>!document.querySelector('#text').disabled,{timeout:15000});};
 try{
  await page.goto(info.url);await page.waitForFunction(()=>document.querySelector('#group').textContent==='vip');await idle();
  check(await page.locator('#owner').textContent()==='Alex','Bound session owner displayed');
  check(await page.locator('#save').isDisabled(),'Empty draft cannot be saved');
  await page.locator('#language').click();check(await page.locator('h1').textContent()==='Префикс, который заметят.','Russian interface');
  await page.locator('#language').click();check(await page.locator('h1').textContent()==='Make your prefix stand out.','English interface');
  await page.locator('#text-override').check();await page.locator('#text').fill('<green>[WEB]</green>');
  await page.waitForFunction(()=>document.querySelector('#text-preview').textContent==='[WEB]');await idle();
  check(await page.locator('#text-preview span').first().evaluate(el=>getComputedStyle(el).color)!=='','Server text preview rendered');
  await page.locator('#text').fill("<img src=x onerror=alert('XSS')>");
  await page.waitForFunction(()=>document.querySelector('#text-preview').textContent.includes('<img'));await idle();
  check(await page.locator('#text-preview img').count()===0,'Untrusted text remains text');
  await page.locator('#text').fill('<gold>[VIP]</gold>');await page.waitForFunction(()=>document.querySelector('#text-preview').textContent==='[VIP]');await idle();
  await page.locator('#file').setInputFiles(path.join(project,'.build/test-png.png'));
  await page.waitForFunction(()=>!document.querySelector('#source-area').hidden&&document.querySelector('#use-image').checked);await idle();
  check(await page.locator('#glyph-preview').evaluate(c=>c.width===4&&c.height===2),'Real PNG preview dimensions');
  const crop=await page.locator('#source-canvas').boundingBox();await page.mouse.move(crop.x+crop.width*.28,crop.y+crop.height*.2);await page.mouse.down();await page.mouse.move(crop.x+crop.width*.78,crop.y+crop.height*.75,{steps:8});await page.mouse.up();
  check(Number(await page.locator('#crop-width').inputValue())>0,'Pointer crop updates coordinates');
  await page.locator('#preview').click();await page.waitForFunction(()=>document.querySelector('#notice').textContent==='Preview updated.');await idle();
  check(!(await page.locator('#notice').getAttribute('class')).includes('error'),'Crop accepted by server');
  await page.locator('#reset-options').click();await page.locator('#file').setInputFiles(path.join(project,'.build/test-gif.gif'));
  await page.waitForFunction(()=>!document.querySelector('#animation-controls').hidden&&document.querySelector('#frame').max==='3');await idle();
  check(await page.locator('#frame-label').textContent().then(s=>s.endsWith('/ 4')),'All GIF frames available');
  await page.locator('#play').click();const frameBefore=await page.locator('#frame-label').textContent();await page.waitForTimeout(350);check(await page.locator('#frame-label').textContent()===frameBefore,'GIF pause');
  await page.locator('#frame').evaluate(el=>{el.value='2';el.dispatchEvent(new Event('input',{bubbles:true}));});check(await page.locator('#frame-label').textContent()==='3 / 4','Manual GIF timeline');
  await page.screenshot({path:path.join(output,'desktop.png'),fullPage:true});
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Desktop has no horizontal overflow');
  await page.locator('#save').click();await page.waitForFunction(()=>!document.querySelector('#save-result').hidden);await idle();
  check(/^[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(await page.locator('#save-code').textContent()),'Browser obtains real save code');
  check((await page.locator('#save-command').textContent()).startsWith('/lptab webpref '),'In-game command displayed');
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(output,'mobile.png'),fullPage:true});
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Mobile has no horizontal overflow');
  check(await page.locator('#save').isVisible(),'Mobile save action accessible');
  const publicPage=await context.newPage();await publicPage.goto(info.url.split('#')[0]);check(await publicPage.locator('#save').isDisabled(),'Editor without token disabled');await publicPage.close();
  check(errors.length===0,'No JavaScript runtime errors: '+errors.join('; '));
  console.log('PASS: '+checks+' browser checks. Chromium '+browser.version()+', desktop/mobile, real upload, GIF timeline, safe text, crop and save code.');
 }finally{await browser.close();if(server){server.stdin.end('\n');await new Promise(resolve=>{server.once('exit',resolve);setTimeout(()=>{server.kill();resolve();},3000).unref();});}}
})().catch(e=>{console.error(e);process.exitCode=1;});
