'use strict';
(() => {
const $ = id => document.getElementById(id);
const token = location.hash.slice(1);
let language = 'ru', session, design, revision = 1, persisted = '', selected = null;
let panel = 'overview', undo = [], redo = [], busy = false, expired = false, paused = false;
let preview = null, previewTimer, previewBusy = false, previewPending = false, generation = 0, drag = null, touch = null;
let previewClock = performance.now(), knownGroups = [];
const copy = value => JSON.parse(JSON.stringify(value));
const ru = {};
document.querySelectorAll('[data-i18n]').forEach(el => ru[el.dataset.i18n] = el.textContent);
ru.heroTitle='Соберите оформление. Сразу увидьте результат.';
const en = {color:'Color',clearStyle:'Clear style',
calm:'Calm TAB',calmHint:'Roles, suffixes and a tidy player list',playerFormatting:'Player names in TAB',playerFormatHint:'One template for the normal player list. Use it in layout cells with {tab_name}.',enablePlayerFormat:'Custom player format',editPlayerFormat:'Edit player format',reverseOrder:'Reverse order',nativeSort:'Sort the normal TAB',nativeSortHint:'Group order applies to the normal list too. Existing teams from other plugins keep priority.',byPriority:'By group priority',byWeight:'By LuckPerms weight',byPing:'By ping',byWorld:'By world and name',groupStyles:'Groups & suffixes',groupStylesHint:'Drag groups to set their priority. Select a group to customize its name color and suffix.',showSuffixes:'Show suffixes',luckSuffixFallback:'LuckPerms suffix for other groups',groupEmpty:'Import groups from LuckPerms or add one manually.',importLuckGroups:'Import LuckPerms groups',addGroupStyle:'+ Group',roleGroup:'Group name in LuckPerms',roleOwnColor:'Custom name color',suffixSource:'Suffix source',customSuffix:'Custom suffix',noSuffix:'Hide suffix',suffixSpacing:'A space before the suffix is added automatically.',suffixBadge:'[Group]',suffixWorld:'World',
bossbars:'Boss bars',bossHint:'Up to 8 bars. Select a bar to edit it, drag to reorder.',bossDisplay:'Display mode',stacked:'All at once',rotate:'One at a time',rotateSeconds:'Switch interval, seconds',bossEmpty:'Your first bar.',bossEmptyHint:'Start with a message, health bar or timer. Every detail can be changed.',addBoss:'+ Boss bar',healthBar:'♡ Health',timerBar:'◷ Timer',bossGamePreview:'In-game boss bars',bossHidden:'No bars are currently visible.',bossPreviewHint:'Preview uses your data, world, permissions and personal settings. Preview timers restart when you edit the draft.',thisBarEnabled:'This bar is enabled',barColor:'Bar color',pink:'Pink',blue:'Blue',red:'Red',green:'Green',yellow:'Yellow',purple:'Purple',white:'White',barStyle:'Bar style',solid:'Solid',barFill:'Fill',manualFill:'Manual',healthFill:'Player health',foodFill:'Player food',experienceFill:'Progress to the next level',onlineFill:'Online / server capacity',customFill:'Custom value',fillTimer:'Filling timer',drainTimer:'Countdown',pulseTimer:'Pulse',fillSource:'Placeholder',fillMax:'Value for a full bar',durationSeconds:'Duration, seconds',repeatTimer:'Repeat timer',hideAfter:'Hide when finished',bossConditions:'Where and who can see it',bossWorlds:'Worlds: one per line',allWorlds:'Empty list means all worlds.',bossPermission:'Required permission',allPlayers:'Empty means all players.',bossEffects:'Minecraft effects',darkenSky:'Darken the sky',bossMusic:'Boss music',bossFog:'Create fog',duplicateBoss:'Duplicate bar',
prefixes:'Prefixes ↗',workspace:'WORKSPACE',overview:'Overview',tab:'TAB & layout',scoreboard:'Scoreboard',nametags:'Name tags',settings:'My settings',connecting:'Connecting…',eyebrow:'MAKE EVERY PART OF YOUR SERVER YOUR OWN',reload:'Reload',savedState:'Saved',apply:'Apply to server',visualEditor:'VISUAL EDITOR',heroTitle:'Build your display. See it take shape.',heroText:'Start with a template, drag blocks into place and adjust the details. Players see your changes after you apply them.',start:'Open the builder →',templates:'Start with a template',templateHint:'Every part can be customized',minimal:'Minimal',minimalHint:'Clean text and quiet colors',neon:'Neon',neonHint:'Gradients and a scrolling message',classic:'Classic',classicHint:'Gold, groups and server information',serverSettings:'Server settings',serverName:'Server name',refresh:'Update interval',yourTemplates:'Your templates',transferHint:'Export your display as JSON or move it to another server.',export:'Export',import:'Import',header:'TAB Header',footer:'TAB Footer',enabled:'Enabled',addLine:'+ Add a line',tabLayout:'TAB layout',layoutHint:'4 columns × 20 rows. Drag cells or blocks from the palette.',fixedLayout:'Fixed layout',textBlock:'+ Text',playerBlock:'+ Player',emptyBlock:'+ Empty',sort:'Players',byName:'By name',byGroup:'By group and name',normalTab:'The normal player list is active. Enable the fixed layout to display this grid.',slotHint:'Select a cell to edit it. ↑ ↓ ← → move the selected cell.',scoreboardTitle:'Sidebar',title:'Title',scoreHint:'Up to 15 lines, including blank lines. Drag to change their order.',preview:'Preview',scoreNumbers:'Hiding scores depends on the client version. Existing scoreboards keep priority.',beforeName:'Before the name',afterName:'After the name',nameHint:'{prefix} uses the group text or image prefix. The player name is added automatically.',visibility:'Visibility',always:'Always',never:'Hidden',otherTeams:'Other teams',ownTeam:'Own team',collision:'Player collisions',neverCollision:'Disabled',namePack:'PNG and GIF prefixes are shown to recipients who have loaded the resource pack. Other recipients keep the configured text fallback.',justYou:'JUST FOR YOU',personalTitle:'Choose your own view.',personalHint:'These switches affect only you. The server must enable the corresponding feature.',saveSettings:'Save my settings',blockSettings:'Block settings',blockType:'Type',text:'Text',player:'Player',empty:'Empty cell',playerIndex:'Player position in the sorted list',formatHelp:'MiniMessage or & color codes. Example: <gold>VIP</gold>.',animation:'Animation',static:'Static',scroll:'Scrolling text',frames:'Frame cycle',speed:'Frame step, seconds',window:'Window width',gap:'Gap, characters',framesHint:'One frame per line',delete:'Delete',result:'RESULT',tabPreview:'In-game TAB',pause:'Pause',previewHint:'The server fills in your data. Fonts and image glyphs depend on the client resource pack.'
};
const dynamic = {
ru:{titles:{overview:'Ваш сервер. Ваш стиль.',tab:'Соберите свой TAB.',scoreboard:'Всё важное — на виду.',nametags:'Группа видна сразу.',settings:'Ваше отображение.'},subs:{overview:'Шаблоны, настройки и визуальный конструктор в одном месте.',tab:'Нажмите блок, измените текст, перетащите на нужное место.',scoreboard:'Соберите боковую панель из простых строк.',nametags:'Текст, изображения и GIF рядом с именем игрока.',settings:'Включите только то, что вам нужно.'},unsaved:'Есть изменения',connected:'Подключено',missing:'Откройте /lptab design или /lptab settings в Minecraft.',expired:'Сессия истекла. Откройте новую ссылку в Minecraft.',saved:'Оформление применено к серверу.',prefsSaved:'Ваши настройки сохранены.',imported:'Шаблон загружен. Проверьте его и нажмите «Применить».',limit:'Достигнут лимит строк.',resumed:'Продолжить',loading:'Применяем…',overwrite:'Этот шаблон заменит текущий черновик. Продолжить?',reloadConfirm:'Загрузить сохранённое оформление и удалить изменения черновика?',select:'Нажмите строку или ячейку, чтобы настроить её.',blank:'Пустая строка',overflow:'Не помещаются в макет: ',conflict:'Другой плагин управляет частью отображения. Посмотрите /lptab doctor.',revision:'Ревизия ',personal:{sidebar:['Скорборд','Боковая панель с информацией'],headerFooter:['Header & Footer','Текст над и под списком TAB'],layout:['Макет TAB','Использовать макет сервера вместо обычного списка'],nameTags:['Префиксы над головой','Видеть оформление рядом с именами'],animations:['Анимация','Бегущие строки, смена кадров и GIF-префиксы']},invalid:'Некорректный файл шаблона.',offline:'Оставайтесь на сервере, пока настраиваете оформление.'},
en:{titles:{overview:'Your server. Your style.',tab:'Build your own TAB.',scoreboard:'Keep the essentials in sight.',nametags:'Make every group visible.',settings:'Your own view.'},subs:{overview:'Templates, settings and a visual builder in one place.',tab:'Select a block, change its text and drag it into place.',scoreboard:'Build a sidebar from simple, reusable lines.',nametags:'Text, images and GIFs beside player names.',settings:'Enable only what you want to see.'},unsaved:'Unsaved changes',connected:'Connected',missing:'Open /lptab design or /lptab settings in Minecraft.',expired:'Session expired. Open a new link in Minecraft.',saved:'Display applied to the server.',prefsSaved:'Your preferences have been saved.',imported:'Template loaded. Review it, then click Apply.',limit:'Line limit reached.',resumed:'Resume',loading:'Applying…',overwrite:'This template replaces your current draft. Continue?',reloadConfirm:'Reload the saved display and discard your draft changes?',select:'Select a line or cell to edit it.',blank:'Blank line',overflow:'Players outside the layout: ',conflict:'Another plugin controls part of the display. Check /lptab doctor.',revision:'Revision ',personal:{sidebar:['Scoreboard','The sidebar with server information'],headerFooter:['Header & Footer','Text above and below the TAB list'],layout:['TAB layout','Use the server layout instead of the normal player list'],nameTags:['Name tags','Prefixes beside player names'],animations:['Animation','Scrolling text, frame cycles and GIF prefixes']},invalid:'Invalid template file.',offline:'Stay online while editing your server display.'}
};
dynamic.ru.titles.bossbars='Настройте свои боссбары.'; dynamic.en.titles.bossbars='Customize your boss bars.';
dynamic.ru.subs.bossbars='Сообщения, здоровье и таймеры — с вашим оформлением.'; dynamic.en.subs.bossbars='Messages, health and timers, with your own style.';
dynamic.ru.personal.bossBars=['Боссбары','Полосы с сообщениями и показателями сверху экрана'];
dynamic.en.personal.bossBars=['Boss bars','Messages and progress bars at the top of the screen'];
dynamic.ru.personal.suffixes=['Суффиксы','Текст и значки после имени в оформлении сервера'];
dynamic.en.personal.suffixes=['Suffixes','Text and badges after player names'];
dynamic.ru.personal.sorting=['Сортировка TAB','Порядок игроков, заданный сервером'];
dynamic.en.personal.sorting=['TAB sorting','Player order chosen by the server'];
dynamic.ru.personal.screen=['Экранные блоки','Текст и картинки в title, subtitle и action bar'];dynamic.en.personal.screen=['Screen elements','Text and images in title, subtitle and action bar'];
dynamic.ru.groupWeight='вес';dynamic.en.groupWeight='weight';
dynamic.ru.duplicateGroup='Такая группа уже добавлена.';dynamic.en.duplicateGroup='This group has already been added.';
dynamic.ru.invalidGroup='Имя группы: латинские буквы, цифры, точка, дефис или подчёркивание.';dynamic.en.invalidGroup='Use letters, digits, dots, hyphens or underscores for the group name.';
dynamic.ru.barReasons={DISABLED:'Полоса выключена',WORLD:'Другой мир',PERMISSION:'Нет права',COMPLETED:'Таймер завершён',SERVER_DISABLED:'Боссбары выключены',PERSONAL_DISABLED:'Выключено в личных настройках',ROTATION:'Ждёт своей очереди'};
dynamic.en.barReasons={DISABLED:'Bar disabled',WORLD:'Different world',PERMISSION:'Permission required',COMPLETED:'Timer finished',SERVER_DISABLED:'Boss bars disabled',PERSONAL_DISABLED:'Disabled in personal settings',ROTATION:'Waiting for its turn'};
function t(key) { return (language === 'en' ? en[key] : ru[key]) || key; }
function d(key) { return dynamic[language][key]; }
function translate() {
 document.documentElement.lang = language;
 document.querySelectorAll('[data-i18n]').forEach(el => el.textContent = t(el.dataset.i18n));
 $('language').textContent = language === 'ru' ? 'EN' : 'RU';
 $('page-title').textContent = d('titles')[panel]; $('page-subtitle').textContent = d('subs')[panel];
 $('pause').textContent = paused ? d('resumed') : t('pause');
 $('boss-pause').textContent = paused ? d('resumed') : t('pause');
 if (session) $('session-state').textContent = d('connected');
 if (session && (session.rgbSupported === false || session.bitmapFontsSupported === false)) {
  $('compatibility-hint').hidden = false;
  $('compatibility-hint').textContent = language === 'ru'
   ? 'Minecraft ' + session.minecraftVersion + ': обычные цвета. ' + (session.bitmapFontsSupported === false ? 'PNG/GIF используют Unicode-текстуры после загрузки пака; один глиф — до 8 пикселей. Строки скорборда и подписи над головой сокращаются по лимитам 1.12.' : 'RGB и градиенты переводятся в ближайшие обычные цвета.')
   : 'Minecraft ' + session.minecraftVersion + ': standard colors. ' + (session.bitmapFontsSupported === false ? 'PNG/GIF use Unicode textures after pack loading; each glyph is up to 8 pixels. Scoreboard lines and name tags fit the 1.12 limits.' : 'RGB and gradients use the nearest standard colors.');
 }
 if (design) { renderLists(); renderGrid(); renderBossBars(); renderGroupRows(); updateDirty(); $('revision').textContent = d('revision') + revision; }
 if (session?.canSettings) renderPersonal();
}
function notice(message, type = '') { $('notice').hidden = false; $('notice').className = 'notice ' + type; $('notice').textContent = message; }
async function api(path, body) {
 const options = {headers:{Authorization:'Bearer ' + token},cache:'no-store'};
 if (body !== undefined) { options.method = 'POST'; options.headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(body); }
 const response = await fetch('api/' + path, options);
 let data; try { data = await response.json(); } catch (_) { throw new Error('HTTP ' + response.status); }
 if (!response.ok) {
  if (response.status === 401) { expired = true; updateDirty(); throw new Error(d('expired')); }
  throw new Error(data.error || 'HTTP ' + response.status);
 }
 return data;
}
function updateDirty() {
 const changed = !!design && JSON.stringify(design) !== persisted;
 $('dirty').textContent = changed ? d('unsaved') : t('savedState'); $('dirty').classList.toggle('unsaved',changed);
 $('apply').disabled = !changed || busy || expired; $('apply').textContent = busy ? d('loading') : t('apply');
 $('undo').disabled = !undo.length; $('redo').disabled = !redo.length;
}
function record() { undo.push(copy(design)); if (undo.length > 40) undo.shift(); redo = []; }
function commit(work, rebuild = false) {
 if (!design || expired || busy) return;
 record(); work(); generation++; previewClock = performance.now(); renderLists(); renderGrid(); renderBossBars(); renderGroupRows(); if (rebuild) inspector(); updateDirty(); schedulePreview(); window.dispatchEvent(new Event('studio-change'));
}
function show(name) {
 if (!session) return;
 if (name !== 'settings' && !session.canDesign || name === 'settings' && !session.canSettings) return;
 panel = name; document.querySelectorAll('.panel').forEach(el => el.hidden = el.id !== 'panel-' + name);
 document.querySelectorAll('.nav').forEach(el => el.classList.toggle('active',el.dataset.panel === name));
 $('design-actions').hidden = name === 'settings';
 if (selected && !((name === 'tab'||name === 'dream') && ['header','footer','slots','groupStyles','tabPlayerFormat'].includes(selected.section) || name === 'scoreboard' && selected.section === 'sidebar' || name === 'bossbars' && selected.section === 'bossBars')) selected = null;
 const mount = name === 'bossbars' ? $('boss-inspector') : name === 'scoreboard' ? $('score-inspector') : (panel === 'dream' ? $('dream-inspector') : $('tab-inspector'));
 mount.append($('inspector')); mount.append($('live-tab')); $('live-tab').hidden = name !== 'tab' && name !== 'dream';
 $('inspector').hidden = !selected || !['tab','dream','scoreboard','bossbars'].includes(name);
 translate();window.dispatchEvent(new Event('studio-change'));
}
function line(text = '') { return {text,animation:'NONE',speed:6,width:28,gap:5,frames:[]}; }
function slot(kind = 'EMPTY', index = 1) { return {kind,playerIndex:index,line:line(kind === 'PLAYER' ? '{tab_name}' : '')}; }
function nextPlayerIndex() { const used = new Set(design.slots.filter(s => s.kind === 'PLAYER').map(s => s.playerIndex)); for (let i = 1; i <= 80; i++) if (!used.has(i)) return i; return 1; }
function selectedLine() { if (!selected) return null; if(selected.section==='tabPlayerFormat')return design.tabPlayerFormat;if(selected.section==='groupStyles')return design.groupStyles[selected.index]?.suffix;return ['slots','bossBars'].includes(selected.section) ? design[selected.section][selected.index]?.line : design[selected.section][selected.index]; }
function selectedBoss() { return selected?.section === 'bossBars' ? design.bossBars[selected.index] : null; }
function selectedRole() { return selected?.section === 'groupStyles' ? design.groupStyles[selected.index] : null; }
function groupStyle(group) { return {group,nameColor:'DEFAULT',suffixMode:'CUSTOM',suffix:line('')}; }
function recommendedStyle(group) {
 const style=groupStyle(group), name=group.toLowerCase();
 const themes=[[/^(owner|founder|создатель)$/, '#f5d58d','✦'],[/^(admin|administrator)$/, '#f3a2ad','◆'],[/^(mod|moderator)$/, '#a1bcf5','✧'],[/^(helper|support)$/, '#93efc4','+'],[/^(vip|premium)$/, '#f5d58d','★'],[/^(mvp|legend)$/, '#9ce3ed','✦']];
 const match=themes.find(([pattern])=>pattern.test(name));
 if(match){style.nameColor=match[1];style.suffix=line('<'+match[1]+'>'+match[2]+'</'+match[1]+'>');}
 else if(!['default','player','user','member'].includes(name))style.suffix=line('<gray>· {group}</gray>');
 return style;
}
function normalizeStyles(value) {
 for(const [key,fallback] of Object.entries({tabFormatEnabled:false,nativeSortEnabled:false,sortReverse:false,suffixEnabled:true,luckSuffixFallback:true}))if(value[key]===undefined)value[key]=fallback;
 value.tabPlayerFormat={...line('{prefix}{display_name}{suffix}'),...value.tabPlayerFormat};
 value.groupStyles=(value.groupStyles||[]).map(role=>({...groupStyle(role.group),...role,suffix:{...line(''),...role.suffix}}));return value;
}
async function loadGroups() {
 knownGroups=await api('display/groups');$('known-groups').replaceChildren();
 for(const role of knownGroups){const option=document.createElement('option');option.value=role.group;option.label=d('groupWeight')+' '+role.weight;$('known-groups').append(option);}
 renderGroupRows();return knownGroups;
}
function renderGroupRows() {
 if(!design)return;const list=$('group-order-list');list.replaceChildren();
 design.groupStyles.forEach((role,index)=>{
  const button=document.createElement('button');button.type='button';button.draggable=true;button.className='group-block'+(selected?.section==='groupStyles'&&selected.index===index?' selected':'');button.dataset.section='groupStyles';button.dataset.index=index;
  const grab=document.createElement('span');grab.className='grab';grab.textContent='⠿';const number=document.createElement('span');number.className='group-priority';number.textContent=String(index+1).padStart(2,'0');
  const content=document.createElement('span');content.className='group-content';const name=document.createElement('strong');name.textContent=role.group;if(/^#[0-9a-f]{6}$/i.test(role.nameColor))name.style.color=role.nameColor;
  const meta=document.createElement('small');const known=knownGroups.find(g=>g.group===role.group);meta.textContent=known?d('groupWeight')+' '+known.weight:'';content.append(name,meta);
  const suffix=document.createElement('span');suffix.className='group-suffix';const sample=preview?.groupStyles?.find(g=>g.group===role.group);if(sample)component(sample.suffix,suffix);else suffix.textContent=role.suffixMode==='LUCKPERMS'?'LuckPerms':role.suffixMode==='NONE'?'—':labelText(role.suffix.text||role.suffix.frames[0]||'');
  button.append(grab,number,content,suffix);button.onclick=()=>select('groupStyles',index);list.append(button);
 });
 $('group-count').textContent=design.groupStyles.length+' / 64';$('group-empty').hidden=design.groupStyles.length>0;$('add-group-style').disabled=design.groupStyles.length>=64;$('import-luck-groups').disabled=design.groupStyles.length>=64;
}
const barColors = {PINK:'#ed69b4',BLUE:'#5b83ed',RED:'#e85858',GREEN:'#73cd56',YELLOW:'#e9cf53',PURPLE:'#af61e3',WHITE:'#eeeeee'};
function barId() { const bytes = new Uint8Array(12); crypto.getRandomValues(bytes); return 'boss-'+Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join(''); }
function newBoss(preset = '') {
 const health=preset==='health',timer=preset==='timer';
 return {id:barId(),enabled:true,color:health?'RED':timer?'YELLOW':'PURPLE',style:health?'SEGMENTED_10':'SOLID',line:line(health?'<red>♡ {player} · {health}/{max_health}</red>':timer?'<gold>◷ {remaining} s · {progress}%</gold>':'<aqua><bold>{server}</bold></aqua> <gray>· {online}/{max} online</gray>'),progressMode:health?'HEALTH':timer?'DRAIN':'STATIC',progress:100,progressSource:'online',progressMax:100,durationTicks:200,loop:true,hideAfter:false,darkenSky:false,playMusic:false,createFog:false,worlds:[],permission:''};
}
function normalizeBosses(value) {
 if(!value.sidebarMode)value.sidebarMode='NATIVE';if(value.sidebarX===undefined)value.sidebarX=72;if(value.sidebarY===undefined)value.sidebarY=-110;
 if(Array.isArray(value.screen))value.screen=value.screen.map(e=>({...e,x:e.x??0,y:e.y??-60,height:e.height??8}));
 if(!Array.isArray(value.screen))value.screen=[];if(value.screenEnabled===undefined)value.screenEnabled=false;
 if (!Array.isArray(value.bossBars)) value.bossBars=[];
 value.bossBars=value.bossBars.map(bar=>({...newBoss(),...bar,line:{...line(''),...bar.line}}));
 if(value.bossBarsEnabled===undefined)value.bossBarsEnabled=false;if(!value.bossBarMode)value.bossBarMode='STACKED';if(value.bossBarRotateTicks===undefined)value.bossBarRotateTicks=200;return value;
}
function barGraphic(title,progress,color,style) {
 const root=document.createElement('span');root.className='boss-graphic';
 const text=document.createElement('span');text.className='boss-title';component(title,text);
 const track=document.createElement('span');track.className='boss-track';track.style.setProperty('--bar-color',barColors[color]||barColors.PURPLE);track.setAttribute('role','progressbar');track.setAttribute('aria-valuemin','0');track.setAttribute('aria-valuemax','100');track.setAttribute('aria-valuenow',String(Math.round(progress*1000)/10));
 const fill=document.createElement('span');fill.className='boss-fill';fill.style.width=(Math.max(0,Math.min(1,progress))*100)+'%';track.append(fill);
 if(style!=='SOLID'){const cuts=document.createElement('span');cuts.className='boss-segments';cuts.style.setProperty('--segments',Number(style.split('_')[1])||10);track.append(cuts);}
 root.append(text,track);return root;
}
function renderBossBars() {
 if(!design)return;const list=$('boss-list');list.replaceChildren();
 design.bossBars.forEach((bar,index)=>{
  const frame=preview?.bossBars?.find(f=>f.id===bar.id),button=document.createElement('button');button.type='button';button.draggable=true;button.className='boss-block'+(selected?.section==='bossBars'&&selected.index===index?' selected':'');button.dataset.section='bossBars';button.dataset.index=index;
  const grab=document.createElement('span');grab.className='grab';grab.textContent='⠿';const content=document.createElement('span');content.className='boss-card-content';
  content.append(barGraphic(frame?.title||labelText(bar.line.text||bar.line.frames[0]||t('text')),frame?.progress??bar.progress/100,bar.color,bar.style));
  const meta=document.createElement('span');meta.className='boss-meta';meta.textContent=String(index+1).padStart(2,'0')+' · '+(frame?.reason?d('barReasons')[frame.reason]||frame.reason:Math.round((frame?.progress??bar.progress/100)*1000)/10+'%');content.append(meta);
  button.append(grab,content);button.onclick=()=>select('bossBars',index);list.append(button);
 });
 $('boss-empty').hidden=design.bossBars.length!==0;$('boss-count').textContent=design.bossBars.length+' / 8';
 $('boss-rotate-field').hidden=design.bossBarMode!=='ROTATE';
 document.querySelectorAll('#add-boss,[data-boss-preset]').forEach(el=>el.disabled=design.bossBars.length>=8);
}
// Preview pulses preserve the draggable roots and pointer capture.
function refreshBossFrames() {
 document.querySelectorAll('#boss-list .boss-block').forEach(button=>{
  const index=Number(button.dataset.index),bar=design.bossBars[index];if(!bar)return;
  const frame=preview?.bossBars?.find(f=>f.id===bar.id),content=button.querySelector('.boss-card-content');
  content.replaceChildren(barGraphic(frame?.title||labelText(bar.line.text||bar.line.frames[0]||t('text')),frame?.progress??bar.progress/100,bar.color,bar.style));
  const meta=document.createElement('span');meta.className='boss-meta';meta.textContent=String(index+1).padStart(2,'0')+' · '+(frame?.reason?d('barReasons')[frame.reason]||frame.reason:Math.round((frame?.progress??bar.progress/100)*1000)/10+'%');content.append(meta);
 });
}
function refreshGroupPreviews() {
 document.querySelectorAll('#group-order-list .group-block').forEach(button=>{
  const role=design.groupStyles[Number(button.dataset.index)];if(!role)return;
  const sample=preview?.groupStyles?.find(g=>g.group===role.group),suffix=button.querySelector('.group-suffix');
  if(sample)fill(suffix,sample.suffix);
 });
}
function addBoss(preset='') {
 if(!design||design.bossBars.length>=8)return;
 commit(()=>{design.bossBars.push(newBoss(preset));design.bossBarsEnabled=true;selected={section:'bossBars',index:design.bossBars.length-1};},true);$('bossbars-enabled').checked=true;
}
const colors = {black:'#000000',dark_blue:'#0000aa',dark_green:'#00aa00',dark_aqua:'#00aaaa',dark_red:'#aa0000',dark_purple:'#aa00aa',gold:'#ffaa00',gray:'#aaaaaa',dark_gray:'#555555',blue:'#5555ff',green:'#55ff55',aqua:'#55ffff',red:'#ff5555',light_purple:'#ff55ff',yellow:'#ffff55',white:'#ffffff'};
function component(node, parent) {
 if (Array.isArray(node)) { node.forEach(n => component(n,parent)); return; }
 const span = document.createElement('span');
 if (typeof node === 'string') span.textContent = node;
 else if (node && typeof node === 'object') {
  span.textContent = node.text || node.translate || '';
  const color = colors[node.color] || node.color; if (color && /^#[0-9a-f]{6}$/i.test(color)) span.style.color = color;
  if (node.bold !== undefined) span.style.fontWeight = node.bold ? 'bold' : 'normal';
  if (node.italic !== undefined) span.style.fontStyle = node.italic ? 'italic' : 'normal';
  const decorations = []; if (node.underlined) decorations.push('underline'); if (node.strikethrough) decorations.push('line-through');
  if (decorations.length) span.style.textDecoration = decorations.join(' ');
  if (node.extra) node.extra.forEach(n => component(n,span));
 }
 parent.append(span);
}
function labelText(source) { return source.replace(/<[^>]+>/g,'').replace(/[&§][0-9a-fklmnorx]/gi,''); }
function fill(el, value) { el.replaceChildren(); component(value,el); }
function renderLists() {
 if (!design) return;
 for (const section of ['header','footer','sidebar']) {
  const container = $(section + '-lines'); container.replaceChildren();
  design[section].forEach((item,index) => {
   const button = document.createElement('button'); button.type = 'button'; button.draggable = true;
   button.className = 'line-block' + (selected?.section === section && selected.index === index ? ' selected' : '');
   button.dataset.section = section; button.dataset.index = index;
   const grab = document.createElement('span'); grab.className = 'grab'; grab.textContent = '⠿';
   const number = document.createElement('span'); number.className = 'line-number'; number.textContent = String(index + 1).padStart(2,'0');
   const content = document.createElement('span'); content.className = 'line-content'; content.textContent = labelText(item.text) || (item.animation === 'FRAMES' ? item.frames[0] : d('blank'));
   button.append(grab,number,content); button.onclick = () => select(section,index); container.append(button);
  });
  document.querySelector('[data-add="' + section + '"]').disabled = design[section].length >= (section === 'sidebar' ? 15 : 12);
 }
}
function renderGrid() {
 if (!design) return; const grid = $('tab-grid'); grid.replaceChildren();
 design.slots.forEach((cell,index) => {
  const button = document.createElement('button'); button.type = 'button'; button.draggable = true;
  button.className = 'tab-slot' + (selected?.section === 'slots' && selected.index === index ? ' selected' : '');
  button.dataset.section = 'slots'; button.dataset.index = index; button.dataset.kind = cell.kind;
  button.setAttribute('aria-label', (language === 'ru' ? 'Столбец ' : 'Column ') + (Math.floor(index / 20) + 1) + ', ' + (index % 20 + 1));
  const grab = document.createElement('span'); grab.className = 'grab'; grab.textContent = '⠿';
  const content = document.createElement('span'); content.className = 'slot-text';
  if (cell.kind === 'PLAYER') content.textContent = '♙ ' + cell.playerIndex;
  else if (cell.kind === 'TEXT') content.textContent = labelText(cell.line.text || cell.line.frames[0] || t('text')); 
  else content.textContent = '·';
  const number = document.createElement('span'); number.className = 'index'; number.textContent = index + 1;
  button.append(grab,content,number); button.onclick = () => select('slots',index); grid.append(button);
 });
 $('layout-disabled').hidden = design.layoutEnabled;
}
function select(section,index) { selected = {section,index}; renderLists(); renderGrid(); renderBossBars(); renderGroupRows(); inspector(); }
function inspector() {
 const l = selectedLine(); $('inspector').hidden = !l || !['tab','dream','scoreboard','bossbars'].includes(panel); if (!l) return;
 const isSlot = selected.section === 'slots', cell = isSlot ? design.slots[selected.index] : null;
 const role=selectedRole(),global=selected.section==='tabPlayerFormat';
 $('selected-label').textContent = global?'PLAYER FORMAT':role?role.group:isSlot ? (Math.floor(selected.index / 20) + 1) + ':' + (selected.index % 20 + 1) : String(selected.index + 1);
 $('slot-fields').hidden = !isSlot;
 $('role-fields').hidden=!role;
 if(role){$('role-group').value=role.group;$('role-color-enabled').checked=role.nameColor!=='DEFAULT';$('role-name-color').value=role.nameColor==='DEFAULT'?'#93efc4':role.nameColor;$('role-name-color').disabled=role.nameColor==='DEFAULT';$('role-suffix-mode').value=role.suffixMode;}
 const editable=!role||role.suffixMode==='CUSTOM';
 for(const id of ['block-text','animation','text-color','format-bold','format-italic','format-reset','animation-speed','animation-width','animation-gap','animation-frames'])$(id).disabled=!editable;
 document.querySelectorAll('#variable-chips button').forEach(el=>{el.disabled=!editable;el.hidden=el.dataset.variable==='tab_name'&&global||el.dataset.variable==='suffix'&&!!role;});
 const boss=selectedBoss();$('boss-fields').hidden=!boss;
 document.querySelectorAll('[data-boss-variable]').forEach(el=>el.hidden=!boss);
 if(boss) {
  $('boss-enabled').checked=boss.enabled;$('boss-color').value=boss.color;$('boss-style').value=boss.style;$('boss-progress-mode').value=boss.progressMode;
  $('boss-progress').value=$('boss-progress-slider').value=boss.progress;$('boss-progress-source').value=boss.progressSource;$('boss-progress-max').value=boss.progressMax;
  $('boss-duration').value=boss.durationTicks/20;$('boss-loop').checked=boss.loop;$('boss-hide-after').checked=boss.hideAfter;
  $('boss-worlds').value=boss.worlds.join('\n');$('boss-permission').value=boss.permission;
  $('boss-darken-sky').checked=boss.darkenSky;$('boss-play-music').checked=boss.playMusic;$('boss-create-fog').checked=boss.createFog;
  $('boss-static-fields').hidden=boss.progressMode!=='STATIC';$('boss-custom-fields').hidden=boss.progressMode!=='CUSTOM';$('boss-timer-fields').hidden=!['FILL','DRAIN','PULSE'].includes(boss.progressMode);$('boss-hide-after-field').hidden=boss.loop;
  $('duplicate-boss').disabled=design.bossBars.length>=8;
  document.querySelectorAll('[data-bar-color]').forEach(el=>{el.classList.toggle('active',el.dataset.barColor===boss.color);el.setAttribute('aria-pressed',String(el.dataset.barColor===boss.color));});
 }
 if (isSlot) { $('slot-kind').value = cell.kind; $('player-index').value = cell.playerIndex; $('player-index-label').hidden = cell.kind !== 'PLAYER'; }
 $('block-text').value = l.text;const color = l.text.match(/<(?:color:)?(#[0-9a-f]{6})>/i);$('text-color').value = color ? color[1] : '#ffffff';$('format-bold').classList.toggle('active',/<bold>/i.test(l.text));$('format-italic').classList.toggle('active',/<italic>/i.test(l.text)); $('animation').value = l.animation; $('animation-speed').value = l.speed/20;
 $('animation-width').value = l.width; $('animation-gap').value = l.gap; $('animation-frames').value = l.frames.join('\n');
 $('animation-fields').hidden = l.animation === 'NONE'; $('scroll-fields').hidden = l.animation !== 'SCROLL'; $('frames-field').hidden = l.animation !== 'FRAMES';
 $('move-left').hidden = $('move-right').hidden = !isSlot;
 $('move-up').hidden=$('move-down').hidden=$('delete-block').hidden=global;
 updateBlockPreview();
}
function updateBlockPreview() { if (!selected) return; const value = selected.section==='groupStyles'?preview?.groupStyles?.find(g=>g.group===selectedRole()?.group)?.suffix:selected.section==='tabPlayerFormat'?preview?.playerFormat:selected.section==='bossBars'?preview?.bossBars?.find(f=>f.id===selectedBoss()?.id)?.title:preview?.[selected.section]?.[selected.index]; if (value !== undefined && value !== null) fill($('block-preview'),value); else $('block-preview').textContent = selectedLine()?.text || ''; }
function repaintForm() {
 normalizeStyles(normalizeBosses(design));previewClock=performance.now();
 $('server-name').value = design.serverName; $('refresh-ticks').value = design.refreshTicks; $('sort').value = design.sort;
 for (const [id,key] of Object.entries({'header-enabled':'headerEnabled','footer-enabled':'footerEnabled','sidebar-enabled':'sidebarEnabled','layout-enabled':'layoutEnabled','nametags-enabled':'nameTagsEnabled','bossbars-enabled':'bossBarsEnabled','tab-format-enabled':'tabFormatEnabled','sort-reverse':'sortReverse','native-sort-enabled':'nativeSortEnabled','suffix-enabled':'suffixEnabled','luck-suffix-fallback':'luckSuffixFallback'})) $(id).checked = design[key];
 $('boss-mode').value=design.bossBarMode;$('boss-rotate-seconds').value=design.bossBarRotateTicks/20;
 for (const [id,key] of Object.entries({'sidebar-title':'title','sidebar-mode':'sidebarMode','sidebar-x':'sidebarX','sidebar-y':'sidebarY','name-prefix':'nameTagPrefix','name-suffix':'nameTagSuffix','name-visibility':'visibility','name-collision':'collision'})) $(id).value = design[key];
 renderLists(); renderGrid(); renderBossBars(); renderGroupRows(); inspector(); $('revision').textContent = d('revision') + revision; updateDirty(); schedulePreview(); window.dispatchEvent(new Event('studio-change'));
}
function reorder(from,to) {
 if (!from || !to || from.section !== to.section) return;
 if (from.index === to.index) return;
 commit(() => {
  const items = design[from.section];
  if (from.section === 'slots') [items[from.index],items[to.index]] = [items[to.index],items[from.index]];
  else items.splice(to.index,0,items.splice(from.index,1)[0]);
  selected = to;
 },true);
}
function move(delta) {
 if (!selected||selected.section==='tabPlayerFormat') return; const items = design[selected.section], to = selected.index + delta;
 if (to < 0 || to >= items.length) return;
 if (selected.section === 'slots' && Math.abs(delta) === 1 && Math.floor(to / 20) !== Math.floor(selected.index / 20)) return;
 reorder(selected,{section:selected.section,index:to});
}
function dropBlock(data,target) {
 if (!target) return;
 if (data.kind) {
  if (target.section !== 'slots') return;
  commit(() => { design.slots[target.index] = slot(data.kind,nextPlayerIndex()); selected = target; },true);
 } else reorder(data,target);
}
const targetOf = el => { const block = el?.closest('[data-section][data-index]'); return block ? {section:block.dataset.section,index:Number(block.dataset.index)} : null; };
document.addEventListener('dragstart',e => {
 const target = targetOf(e.target), palette = e.target.closest('[data-kind]');
 drag = target || (palette ? {kind:palette.dataset.kind} : null); if (!drag) return;
 e.dataTransfer.setData('application/x-tabprefix',JSON.stringify(drag)); e.dataTransfer.effectAllowed = 'move';
});
document.addEventListener('dragover',e => { if (!drag) return; const target = targetOf(e.target); if (!target || !drag.kind && drag.section !== target.section || drag.kind && target.section !== 'slots') return; e.preventDefault(); e.target.closest('[data-section]').classList.add('drop-target'); });
document.addEventListener('dragleave',e => e.target.closest('[data-section]')?.classList.remove('drop-target'));
document.addEventListener('drop',e => { if (!drag) return; e.preventDefault(); dropBlock(drag,targetOf(e.target)); drag = null; document.querySelectorAll('.drop-target').forEach(el => el.classList.remove('drop-target')); });
document.addEventListener('dragend',() => { drag = null; document.querySelectorAll('.drop-target').forEach(el => el.classList.remove('drop-target')); });
document.addEventListener('pointerdown',e => {
 if (e.pointerType === 'mouse' || !e.target.closest('.grab')) return;
 const source = targetOf(e.target); if (!source) return;
 e.preventDefault(); touch = {source,startX:e.clientX,startY:e.clientY,target:source,moved:false}; e.target.setPointerCapture(e.pointerId);
});
document.addEventListener('pointermove',e => {
 if (!touch) return; if (Math.hypot(e.clientX-touch.startX,e.clientY-touch.startY) > 8) touch.moved = true;
 if (!touch.moved) return; const el = document.elementFromPoint(e.clientX,e.clientY), target = targetOf(el);
 document.querySelectorAll('.drop-target').forEach(x => x.classList.remove('drop-target'));
 if (target?.section === touch.source.section) { touch.target = target; el.closest('[data-section]').classList.add('drop-target'); }
});
function endTouch() { if (!touch) return; if (touch.moved) reorder(touch.source,touch.target); else select(touch.source.section,touch.source.index); touch = null; document.querySelectorAll('.drop-target').forEach(el => el.classList.remove('drop-target')); }
document.addEventListener('pointerup',endTouch); document.addEventListener('pointercancel',() => touch = null);
function schedulePreview() { clearTimeout(previewTimer); previewTimer = setTimeout(fetchPreview,400); }
async function fetchPreview() {
 if (!design || !session?.canDesign || expired || document.hidden) return;
 if (previewBusy) { previewPending = true; return; }
 previewBusy = true; const version = generation;
 try {
  const data = await api('display/preview',{design,tick:paused ? 0 : Math.floor((performance.now()-previewClock)/50) % 100000000});
  if (version !== generation) { previewPending = true; return; }
  preview = data; renderPreview(); window.dispatchEvent(new Event('studio-preview'));
 } catch (error) { notice(error.message,'error'); }
 finally { previewBusy = false; if (previewPending) { previewPending = false; schedulePreview(); } }
}
function renderPreview() {
 if (!preview || !design) return;
 fill($('score-preview-title'),preview.title); const lines = $('score-preview-lines'); lines.replaceChildren();
 preview.sidebar.forEach((value,index) => { const el = document.createElement('div'); el.className = 'score-line'; const text = document.createElement('span'); component(value,text); el.append(text);if(design.sidebarMode==='NATIVE'&&(!session.blankScoresSupported||preview.scoreNumbers==='adapter-unavailable')){const score=document.createElement('em');score.textContent=15-index;el.append(score);} lines.append(el); });
 fill($('nametag-preview'),preview.nameTag); $('nametag-preview').style.opacity = design.visibility === 'NEVER' ? '.25' : '1';
 const stack=$('boss-preview-stack');stack.replaceChildren();for(const bar of preview.bossBars||[]){if(bar.active)stack.append(barGraphic(bar.title,bar.progress,bar.color,bar.style));}$('boss-preview-empty').hidden=stack.childElementCount>0;refreshBossFrames();
 for (const type of ['header','footer']) { const container = $('tab-preview-' + type); container.replaceChildren(); if (design[type+'Enabled']) preview[type].forEach(value => { const el = document.createElement('div'); component(value,el); container.append(el); }); }
 const grid = $('tab-preview-slots');grid.replaceChildren();const values=design.layoutEnabled?preview.slots:(preview.players||preview.slots);const columns=design.layoutEnabled?4:Math.max(1,Math.min(4,Math.ceil(values.length/20))),rows=design.layoutEnabled?20:Math.max(1,Math.ceil(values.length/columns));grid.style.gridTemplateColumns='repeat('+columns+',minmax(0,1fr))';grid.style.gridTemplateRows='repeat('+rows+',15px)';values.forEach(value=>{const el=document.createElement('div');component(value,el);grid.append(el);});
 for(const section of ['header','footer','sidebar'])document.querySelectorAll('.line-block[data-section="'+section+'"]').forEach(el=>{const value=preview[section]?.[Number(el.dataset.index)];if(value){const content=el.querySelector('.line-content');fill(content,value);if(!content.textContent)content.textContent=d('blank');}});
 document.querySelectorAll('.tab-slot[data-kind="TEXT"]').forEach(el=>{const value=preview.slots?.[Number(el.dataset.index)];if(value)fill(el.querySelector('.slot-text'),value);});
 $('overflow').textContent = preview.overflow ? d('overflow') + preview.overflow : '';
 fill($('player-format-preview'),preview.playerFormat||'');refreshGroupPreviews();$('sort-conflicts').hidden=!preview.sortConflicts;$('sort-conflicts').textContent=d('conflict');
 $('conflicts').hidden = !preview.sidebarConflicts && !preview.nameTagConflicts && preview.layoutAvailable;
 $('conflicts').textContent = d('conflict');$('score-conflicts').hidden=!preview.sidebarConflicts;$('score-conflicts').textContent=d('conflict');$('layout-conflicts').hidden=preview.layoutAvailable&&!preview.headerConflicts;$('layout-conflicts').textContent=d('conflict');updateBlockPreview();
}
async function preset(name) {
 if(!design||busy||expired)return;
 if (JSON.stringify(design) !== persisted && !window.confirm(d('overwrite'))) return;
 if(name==='calm'){
  try{await loadGroups();}catch(error){notice(error.message,'error');return;}
  commit(()=>{
   design.headerEnabled=design.footerEnabled=design.tabFormatEnabled=design.nativeSortEnabled=design.suffixEnabled=design.luckSuffixFallback=true;
   design.layoutEnabled=false;design.sort='PRIORITY';design.sortReverse=false;
   design.header=[line('<#93efc4><bold>{server}</bold></#93efc4>'),line('<gray>{online}<dark_gray> / </dark_gray>{max} <dark_gray>·</dark_gray> {world}</gray>')];
   design.footer=[line('<dark_gray>───────────────</dark_gray>'),line('<gray>{player} <dark_gray>·</dark_gray> <#93efc4>{ping} ms</#93efc4></gray>')];
   design.tabPlayerFormat=line('{prefix}{display_name}{suffix}');design.groupStyles=knownGroups.slice(0,64).map(g=>recommendedStyle(g.group));
   design.slots=Array.from({length:80},(_,i)=>slot('PLAYER',i+1));if(!design.nameTagSuffix)design.nameTagSuffix='{suffix}';selected=null;
  });repaintForm();show('tab');return;
 }
 commit(() => {
  const accent = name === 'classic' ? 'gold' : name === 'neon' ? 'aqua' : 'white';
  design.headerEnabled = design.footerEnabled = design.sidebarEnabled = design.nameTagsEnabled = true;
  design.layoutEnabled = name !== 'minimal';
  design.header = [line('<'+accent+'><bold>{server}</bold></'+accent+'>'),line('<gray>{online}/{max} online · {world}</gray>')];
  if (name === 'neon') design.header[0] = line('<gradient:#93efc4:#95acff><bold>{server}</bold></gradient>');
  design.footer = [line('<gray>{player} · <'+accent+'>{ping} ms</'+accent+'></gray>')];
  design.title = '<'+accent+'><bold>{server}</bold></'+accent+'>';
  if (name === 'neon') design.title = '<gradient:#93efc4:#95acff><bold>{server}</bold></gradient>';
  design.sidebar = [line(''),line('<gray>Player <white>{player}</white></gray>'),line('<gray>Group <'+accent+'>{group}</'+accent+'></gray>'),line(''),line('<gray>Online <green>{online}/{max}</green></gray>'),line('<gray>World <white>{world}</white></gray>'),line('<gray>Ping <green>{ping} ms</green></gray>'),line(''),line('<'+accent+'>{server}</'+accent+'>')];
  design.slots = Array.from({length:80},(_,i) => slot('PLAYER',i+1));
  if (design.layoutEnabled) {
   let n = 1;
   design.slots = design.slots.map((s,i) => {
    if (i % 20 === 0) { const c = slot('TEXT'); c.line = line('<'+accent+'><bold>'+['{server}','PLAYERS','INFO','COMMUNITY'][Math.floor(i/20)]+'</bold></'+accent+'>'); return c; }
    return slot('PLAYER',n++);
   });
  }
  if (name === 'neon') { const l = line('<aqua>✦ Welcome to {server} · Build, explore and play together!</aqua>'); l.animation = 'SCROLL'; l.width = 40; design.footer.push(l); }
  design.nameTagPrefix = '{prefix}'; design.nameTagSuffix = ''; selected = null;
 }); repaintForm(); show('tab');
}
let personal = {};
function renderPersonal() {
 const root = $('personal-toggles'); root.replaceChildren();
 for (const [key,labels] of Object.entries(d('personal'))) {
  const row = document.createElement('div'); row.className = 'personal-toggle';
  const label = document.createElement('label'); label.htmlFor = 'pref-' + key; label.textContent = labels[0];
  const hint = document.createElement('small'); hint.textContent = labels[1]; label.append(hint);
  const input = document.createElement('input'); input.type = 'checkbox'; input.id = 'pref-'+key; input.checked = personal[key] !== false;
  input.onchange = () => personal[key] = input.checked; row.append(label,input); root.append(row);
 }
}
$('save-settings').onclick = async () => {
 if (busy || expired) return; busy = true; notice(d('loading')); $('save-settings').disabled = true;
 try { personal = await api('preferences',personal); notice(d('prefsSaved'),'success'); }
 catch (error) { notice(error.message,'error'); }
 finally { busy = false; $('save-settings').disabled = expired; }
};
document.querySelectorAll('[data-panel]').forEach(el => el.onclick = () => show(el.dataset.panel));
document.querySelectorAll('[data-go]').forEach(el => el.onclick = () => show(el.dataset.go));
document.querySelectorAll('[data-preset]').forEach(el => el.onclick = () => preset(el.dataset.preset));
document.querySelectorAll('[data-add]').forEach(el => el.onclick = () => {
 const section = el.dataset.add; if (design[section].length >= (section === 'sidebar' ? 15 : 12)) { notice(d('limit')); return; }
 commit(() => { design[section].push(line('')); selected = {section,index:design[section].length-1}; },true);
});
document.querySelectorAll('.palette [data-kind]').forEach(el => el.onclick = () => {
 if (selected?.section !== 'slots') select('slots',0);
 dropBlock({kind:el.dataset.kind},selected);
});
for (const [id,key] of Object.entries({'header-enabled':'headerEnabled','footer-enabled':'footerEnabled','sidebar-enabled':'sidebarEnabled','layout-enabled':'layoutEnabled','nametags-enabled':'nameTagsEnabled','bossbars-enabled':'bossBarsEnabled','tab-format-enabled':'tabFormatEnabled','sort-reverse':'sortReverse','native-sort-enabled':'nativeSortEnabled','suffix-enabled':'suffixEnabled','luck-suffix-fallback':'luckSuffixFallback'})) $(id).onchange = () => commit(() => design[key] = $(id).checked);
$('edit-player-format').onclick=()=>select('tabPlayerFormat',0);
$('role-group').onchange=()=>{
 const role=selectedRole(),name=$('role-group').value.trim().toLowerCase();if(!role)return;
 if(!/^[a-z0-9_.-]{1,128}$/.test(name)){notice(d('invalidGroup'),'error');$('role-group').value=role.group;return;}
 if(design.groupStyles.some(r=>r!==role&&r.group===name)){notice(d('duplicateGroup'),'error');$('role-group').value=role.group;return;}
 commit(()=>role.group=name,true);
};
$('role-color-enabled').onchange=()=>commit(()=>selectedRole().nameColor=$('role-color-enabled').checked?$('role-name-color').value:'DEFAULT',true);
$('role-name-color').oninput=()=>commit(()=>selectedRole().nameColor=$('role-name-color').value);
$('role-suffix-mode').onchange=()=>commit(()=>selectedRole().suffixMode=$('role-suffix-mode').value,true);
document.querySelectorAll('[data-suffix-snippet]').forEach(el=>el.onclick=()=>commit(()=>{const role=selectedRole();role.suffixMode='CUSTOM';role.suffix=line(el.dataset.suffixSnippet==='badge'?'<gold>[{group}]</gold>':el.dataset.suffixSnippet==='world'?'<gray>· {world}</gray>':'<aqua>✦</aqua>');},true));
$('add-group-style').onclick=()=>{
 if(design.groupStyles.length>=64)return;const available=knownGroups.find(g=>!design.groupStyles.some(r=>r.group===g.group));let name=available?.group||'new-group',i=2;while(design.groupStyles.some(r=>r.group===name))name='new-group-'+i++;
 commit(()=>{design.groupStyles.push(groupStyle(name));selected={section:'groupStyles',index:design.groupStyles.length-1};},true);$('role-group').focus();$('role-group').select();
};
$('import-luck-groups').onclick=async()=>{
 if(busy||expired)return;busy=true;updateDirty();
 try{await loadGroups();busy=false;commit(()=>{for(const role of knownGroups){if(design.groupStyles.length>=64)break;if(!design.groupStyles.some(r=>r.group===role.group))design.groupStyles.push(recommendedStyle(role.group));}},true);}
 catch(error){notice(error.message,'error');}finally{busy=false;updateDirty();}
};
$('add-boss').onclick=()=>addBoss();document.querySelectorAll('[data-boss-preset]').forEach(el=>el.onclick=()=>addBoss(el.dataset.bossPreset));
$('boss-mode').onchange=()=>commit(()=>design.bossBarMode=$('boss-mode').value);
$('boss-rotate-seconds').onchange=()=>commit(()=>design.bossBarRotateTicks=Math.round(Number($('boss-rotate-seconds').value)*20));
for(const [id,key] of Object.entries({'boss-color':'color','boss-style':'style','boss-progress-mode':'progressMode','boss-progress-source':'progressSource'}))$(id).onchange=()=>commit(()=>selectedBoss()[key]=$(id).value,true);
for(const [id,key] of Object.entries({'boss-enabled':'enabled','boss-loop':'loop','boss-hide-after':'hideAfter','boss-darken-sky':'darkenSky','boss-play-music':'playMusic','boss-create-fog':'createFog'}))$(id).onchange=()=>commit(()=>selectedBoss()[key]=$(id).checked,true);
for(const [id,key] of Object.entries({'boss-progress-max':'progressMax','boss-duration':'durationTicks'}))$(id).onchange=()=>commit(()=>selectedBoss()[key]=key==='durationTicks'?Math.round(Number($(id).value)*20):Number($(id).value));
for(const id of ['boss-progress','boss-progress-slider'])$(id).oninput=()=>commit(()=>{const value=Number($(id).value);selectedBoss().progress=value;$('boss-progress').value=$('boss-progress-slider').value=value;});
$('boss-worlds').oninput=()=>commit(()=>selectedBoss().worlds=$('boss-worlds').value.split('\n').map(s=>s.trim()).filter(Boolean));
$('boss-permission').oninput=()=>commit(()=>selectedBoss().permission=$('boss-permission').value.trim());
$('duplicate-boss').onclick=()=>{if(design.bossBars.length>=8)return;commit(()=>{const bar=copy(selectedBoss());bar.id=barId();design.bossBars.splice(selected.index+1,0,bar);selected.index++;},true);};
for(const [name,color] of Object.entries(barColors)){const button=document.createElement('button');button.type='button';button.dataset.barColor=name;button.style.setProperty('--swatch',color);button.title=t(name.toLowerCase());button.setAttribute('aria-label',name);button.onclick=()=>commit(()=>selectedBoss().color=name,true);$('boss-swatches').append(button);}
for(const key of ['online','max','health','max_health','food','experience','level','ping','x','y','z','layout_overflow']){const option=document.createElement('option');option.value=key;option.textContent='{'+key+'}';$('boss-progress-source').append(option);}
$('inspector').insertBefore($('boss-fields'),$('variable-chips'));
$('inspector').insertBefore($('role-fields'),$('slot-fields'));
const tabCanvas=document.querySelector('#panel-tab .canvas-column'),layoutCard=tabCanvas.querySelector('.layout-card');
for(const id of ['tab-format-card','group-styles-card']){tabCanvas.insertBefore($(id),layoutCard);$(id).hidden=false;}
$('sort-mount').append($('sort').closest('label'));
for (const [id,key] of Object.entries({'server-name':'serverName','sidebar-title':'title','name-prefix':'nameTagPrefix','name-suffix':'nameTagSuffix'})) $(id).oninput = () => commit(() => design[key] = $(id).value);
for (const [id,key] of Object.entries({'name-visibility':'visibility','name-collision':'collision','sort':'sort','refresh-ticks':'refreshTicks'})) $(id).onchange = () => commit(() => design[key] = id === 'refresh-ticks' ? Number($(id).value) : $(id).value);
$('sidebar-mode').onchange=()=>commit(()=>design.sidebarMode=$('sidebar-mode').value);
for(const [id,key,min,max]of[['sidebar-x','sidebarX',-512,512],['sidebar-y','sidebarY',-512,352]])$(id).oninput=()=>{const n=Number($(id).value);if(Number.isInteger(n)&&n>=min&&n<=max)commit(()=>design[key]=n);};
$('slot-kind').onchange = () => commit(() => { const cell = design.slots[selected.index]; cell.kind = $('slot-kind').value; if (cell.kind === 'PLAYER' && !cell.line.text) cell.line.text = '{tab_name}'; },true);
$('player-index').onchange = () => commit(() => design.slots[selected.index].playerIndex = Math.max(1,Math.min(80,Number($('player-index').value))),true);

function clearFormatting(source) { return source.replace(/<\/?(?:bold|italic|underlined|strikethrough|gradient(?::[^>]*)?|rainbow(?::[^>]*)?|color(?::[^>]*)?|#[0-9a-f]{6}|black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white)>/gi,'').replace(/[&§][0-9a-fklmnor]/gi,''); }
$('text-color').oninput = () => commit(() => { const l=selectedLine();l.text='<'+$('text-color').value+'>'+clearFormatting(l.text)+'</'+$('text-color').value+'>'; },true);
$('format-bold').onclick = () => commit(() => { const l=selectedLine();l.text=/<bold>/i.test(l.text)?l.text.replace(/<\/?bold>/gi,''):'<bold>'+l.text+'</bold>'; },true);
$('format-italic').onclick = () => commit(() => { const l=selectedLine();l.text=/<italic>/i.test(l.text)?l.text.replace(/<\/?italic>/gi,''):'<italic>'+l.text+'</italic>'; },true);
$('format-reset').onclick = () => commit(() => selectedLine().text=clearFormatting(selectedLine().text),true);
$('block-text').oninput = () => commit(() => selectedLine().text = $('block-text').value);
$('animation').onchange = () => commit(() => { const l = selectedLine(); l.animation = $('animation').value; if (l.animation === 'FRAMES' && !l.frames.length) l.frames = [l.text || 'Frame 1',l.text || 'Frame 2']; },true);
for (const [id,key] of Object.entries({'animation-speed':'speed','animation-width':'width','animation-gap':'gap'})) $(id).onchange = () => commit(() => selectedLine()[key] = id === 'animation-speed' ? Math.round(Number($(id).value)*20) : Number($(id).value));
$('animation-frames').oninput = () => commit(() => selectedLine().frames = $('animation-frames').value.split('\n').slice(0,32));
for (const key of ['player','prefix','suffix','display_name','tab_name','group','weight','server','online','max','world','ping','time','date','health','max_health','food','experience','level','x','y','z','layout_overflow','progress','remaining']) {
 const button = document.createElement('button'); button.dataset.variable=key;button.textContent = '{'+key+'}'; button.onclick = () => {
  if (!selectedLine()) return; const input = $('block-text'),start = input.selectionStart,end = input.selectionEnd;
  commit(() => selectedLine().text = input.value.slice(0,start)+'{'+key+'}'+input.value.slice(end));
  input.value = selectedLine().text; input.focus(); input.setSelectionRange(start+key.length+2,start+key.length+2);
 }; if(['progress','remaining'].includes(key))button.dataset.bossVariable='true';$('variable-chips').append(button);
}
$('move-up').onclick = () => move(-1); $('move-down').onclick = () => move(1); $('move-left').onclick = () => move(-20); $('move-right').onclick = () => move(20);
$('delete-block').onclick = () => commit(() => {
 if (selected.section === 'slots') design.slots[selected.index] = slot();
 else { design[selected.section].splice(selected.index,1); selected = null; }
},true);
function travel(from,to) { if (!from.length) return; to.push(copy(design)); design = from.pop(); generation++; selected = null; repaintForm(); }
$('undo').onclick = () => travel(undo,redo); $('redo').onclick = () => travel(redo,undo);
document.addEventListener('keydown',e => {
 if (['INPUT','TEXTAREA','SELECT'].includes(e.target.tagName)) return;
 if (session?.canDesign && (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); travel(e.shiftKey ? redo : undo,e.shiftKey ? undo : redo); }
 if (selected && e.target.closest('#tab-grid,.line-list,.boss-list,.group-list')) {
  const delta = {ArrowUp:-1,ArrowDown:1,ArrowLeft:-20,ArrowRight:20}[e.key];
  if (delta !== undefined) { e.preventDefault(); move(delta); const el = document.querySelector('[data-section="'+selected.section+'"][data-index="'+selected.index+'"]'); el?.focus(); }
 }
});
$('apply').onclick = async () => {
 if (busy || expired || !design) return;
 const payload = copy(design),version = generation; busy = true; updateDirty();
 notice(d('loading'));
 try { const data = await api('display/design',{design:payload,revision}); revision = data.revision; persisted = JSON.stringify(payload); $('revision').textContent = d('revision')+revision; notice(d('saved'),'success'); if (version === generation) { design = data.design; persisted = JSON.stringify(design); } }
 catch (error) { notice(error.message,'error'); }
 finally { busy = false; updateDirty(); window.dispatchEvent(new Event('studio-saved')); }
};
$('reload').onclick = async () => {
 if (busy || expired || JSON.stringify(design) !== persisted && !window.confirm(d('reloadConfirm'))) return;
 busy = true; updateDirty();
 try { const data = await api('display/design'); design = data.design; revision = data.revision; persisted = JSON.stringify(design); generation++; selected = null; undo = []; redo = []; repaintForm(); }
 catch (error) { notice(error.message,'error'); }
 finally { busy = false; updateDirty(); }
};
$('export').onclick = () => {
 const blob = new Blob([JSON.stringify(design,null,2)+'\n'],{type:'application/json'}),url = URL.createObjectURL(blob),a = document.createElement('a');
 a.href = url; a.download = 'tabprefix-display.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url),1000);
};
$('import').onclick = () => $('import-file').click();
$('import-file').onchange = async e => {
 const file = e.target.files[0]; if (!file) return;
 try {
  if (file.size > 65536) throw new Error(d('invalid'));
  const data = JSON.parse(await file.text());
  if (data.schema !== 1 || !Array.isArray(data.slots) || data.slots.length !== 80 || !['header','footer','sidebar'].every(k => Array.isArray(data[k]))) throw new Error(d('invalid'));
  await api('display/preview',{design:data,tick:0});
  record(); design = normalizeStyles(normalizeBosses(data)); selected = null; generation++; repaintForm(); notice(d('imported'),'success');
 } catch (error) { notice(error.message,'error'); } finally { e.target.value = ''; }
};
$('language').onclick = () => { language = language === 'en' ? 'ru' : 'en'; translate(); inspector(); renderPreview();window.dispatchEvent(new Event('studio-change')); };
function pausePreview() { paused = !paused; $('pause').textContent = $('boss-pause').textContent = paused ? d('resumed') : t('pause'); schedulePreview(); }
$('pause').onclick = $('boss-pause').onclick = pausePreview;
window.addEventListener('beforeunload',e => { if (design && JSON.stringify(design) !== persisted) { e.preventDefault(); e.returnValue = ''; } });
async function initialize() {
 document.querySelector('main').inert=true;document.querySelectorAll('.nav').forEach(el=>el.disabled=true);
 translate();
 if (!/^[A-Za-z0-9_-]{43}$/.test(token)) { expired = true; notice(d('missing'),'error'); $('design-actions').hidden = true; document.querySelectorAll('main button,.nav').forEach(el => el.disabled = true); return; }
 try {
  session = await api('session'); language = session.language === 'en' ? 'en' : 'ru'; $('owner').textContent = session.player;
  document.querySelectorAll('.nav').forEach(el => el.hidden = el.dataset.panel === 'settings' ? !session.canSettings : !session.canDesign);
  if (session.canPrefixes) { $('prefix-link').hidden = false; $('prefix-link').href = 'editor#'+token; }
  if (session.canDesign) { const data = await api('display/design'); design = normalizeStyles(normalizeBosses(data.design)); revision = data.revision; persisted = JSON.stringify(design);await loadGroups(); repaintForm(); }
  if (session.canSettings) { personal = await api('preferences'); renderPersonal(); }
  if (!session.canDesign && !session.canSettings) throw new Error(d('missing'));
  document.querySelector('main').inert=false;document.querySelectorAll('.nav').forEach(el=>el.disabled=false);show(session.canDesign ? 'overview' : 'settings');
  if (session.canDesign) setInterval(() => { if (!paused) fetchPreview(); },1500);
 } catch (error) { notice(error.message,'error'); $('design-actions').hidden = true; }
}
window.TabPrefixStudio={get state(){return {design,session,language,panel,preview,expired,busy,unsaved:!!design&&JSON.stringify(design)!==persisted};},commit,show,line,slot,select,repaintForm,api,notice,component,preview:schedulePreview,acceptNavigation:()=>{persisted=JSON.stringify(design);}};
initialize();
})();
