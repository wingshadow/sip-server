/* 公共工具与导航：所有页面引用 */

/* 媒体模式（local / zlm），由 /api/status 的 mediaMode 字段更新 */
let mediaMode = 'local';

function isZlmMode(){
    return String(mediaMode||'').toLowerCase()==='zlm';
}

/*
 * 应用 /api/status 结果：
 * - 更新全局 mediaMode
 * - 页面上若有 #mediaMode 元素则同步显示
 */
function applyStatus(status){
    if(!status){
        return;
    }

    mediaMode = String(status.mediaMode||'local').toLowerCase();

    const el=document.getElementById('mediaMode');
    if(el){
        el.textContent = mediaMode==='zlm' ? 'zlm（ZLMediaKit）' : 'local（多端口 RTP）';
    }
}

function esc(v){
    return String(v??'').replace(/[&<>'"]/g,s=>({
        '&':'&amp;',
        '<':'&lt;',
        '>':'&gt;',
        "'":'&#39;',
        '"':'&quot;'
    }[s]));
}

function formatDate(v){
    if(!v){
        return '-';
    }

    const d=new Date(v);

    return isNaN(d.getTime())
        ? String(v)
        : d.toLocaleString('zh-CN');
}

/* epoch 时间格式化：自动区分秒（<=1e11）与毫秒 */
function formatEpoch(v){
    if(!v){
        return '-';
    }

    const n=Number(v);

    if(!Number.isFinite(n)||n<=0){
        return String(v);
    }

    const millis = n>1e11 ? n : n*1000;

    return new Date(millis).toLocaleString('zh-CN');
}

/*
 * 读取响应体：
 * - 正常响应解析 JSON 返回
 * - 错误响应若带 {code,message} 业务体也返回（调用方按 code 判断）
 * - 其余错误响应抛 HTTP 异常
 */
function readResponse(r){
    return r.text().then(text=>{
        let data = null;

        try{
            data = text ? JSON.parse(text) : null;
        }catch(e){
            data = null;
        }

        if(!r.ok && (data === null || data.code === undefined)){
            throw new Error('HTTP ' + r.status);
        }

        return data;
    });
}

/* GET JSON 快捷方法（禁缓存） */
function fetchJson(url){
    return fetch(url,{cache:'no-store'})
        .then(readResponse);
}

/* POST JSON 快捷方法：body 为对象（application/json） */
function postJson(url, body){
    return fetch(url,{
        method:'POST',
        cache:'no-store',
        headers:{
            'Content-Type':'application/json'
        },
        body: body===undefined ? '{}' : JSON.stringify(body)
    }).then(readResponse);
}

/*
 * MSE FLV 播放能力检测（mpegts.js 版本兼容）。
 *
 * 项目内置 mpegts.min.js 为旧版构建（flv.js 1.2.x 系）：
 *   - 提供 isSupported() 与 getFeatureList()
 *   - getFeatureList() 的能力键为 msePlayback / mseLivePlayback，
 *     没有新版的 mseFlvPlayback 键
 * 若直接读 getFeatureList().mseFlvPlayback 会恒为 undefined，
 * 导致误报"浏览器不支持 MSE FLV 播放"（直播/回放均无法起播）。
 *
 * 这里按优先级兼容三种形态：
 *   1. isSupported()            —— 旧版（1.2-1.3）与新版（1.4+）均有
 *   2. getFeatureList().mseFlvPlayback —— 新版键名
 *   3. getFeatureList().msePlayback     —— 旧版键名
 */
function mseFlvSupported(){
    if(typeof mpegts==='undefined' || !mpegts){
        return false;
    }

    try{
        if(typeof mpegts.isSupported==='function'){
            return !!mpegts.isSupported();
        }

        if(typeof mpegts.getFeatureList==='function'){
            const f=mpegts.getFeatureList()||{};

            return !!(f.mseFlvPlayback || f.msePlayback);
        }
    }catch(e){
        return false;
    }

    return false;
}

/* 会话状态标签（TALKING/PLAYING 绿，INVITED 橙，其余灰） */
function stateTag(state, reason){
    const s = String(state||'-');

    if(s==='TALKING'||s==='PLAYING'){
        return '<span class="tag">'+esc(s)+'</span>';
    }

    if(s==='INVITED'){
        return '<span class="tag orange">'+esc(s)+'</span>';
    }

    return '<span class="tag gray">'+esc(s)+'</span>'+
        (reason
            ?' <span class="muted">'+esc(reason)+'</span>'
            :''
        );
}

/* 顶部导航（页面 body 顶部放 <div id="nav"></div>） */
const NAV_ITEMS=[
    {key:'index',href:'/index.html',label:'总览'},
    {key:'live',href:'/live.html',label:'实时预览'},
    {key:'playback',href:'/playback.html',label:'历史回放'},
    {key:'voice',href:'/voice.html',label:'语音对讲'}
];

function renderNav(active){
    const nav=document.getElementById('nav');

    if(!nav){
        return;
    }

    nav.innerHTML='<div class="nav">'+
        NAV_ITEMS.map(i=>
            '<a class="nav-link'+
            (i.key===active?' active':'')+
            '" href="'+i.href+'">'+
            i.label+
            '</a>'
        ).join('')+
        '<a class="nav-link gray" href="javascript:void(0)" onclick="logout()">退出登录</a>'+
        '</div>';
}

/* 读取 URL 查询参数（跨页传递设备/通道上下文） */
function urlParams(){
    return new URLSearchParams(location.search);
}

/*
 * 加载设备下拉框。
 *
 * selectEl: <select> 元素
 * opts: { keep: 保留当前值（默认 true）, emptyLabel: 空选项文案 }
 *
 * 选项值为 deviceId；设备注册后 catalog 通道由后端解析，
 * 单通道摄像头 channelId 即 deviceId（与后端 resolveChannelId 语义一致）。
 */
async function fillDeviceSelect(selectEl, opts){
    if(!selectEl){
        return;
    }

    const keep = !opts || opts.keep!==false;
    const emptyLabel = (opts && opts.emptyLabel) || '请选择设备';

    const oldValue = selectEl.value;

    try{
        const devices = await fetchJson('/api/devices') || [];

        selectEl.innerHTML =
            '<option value="">'+esc(emptyLabel)+'</option>'+
            devices.map(d=>{
                const id = d.deviceId||'';
                const name = d.name ? d.name+'（'+id+'）' : id;
                return '<option value="'+esc(id)+'">'+esc(name)+'</option>';
            }).join('');

        if(keep && oldValue){
            selectEl.value = oldValue;
        }
    }catch(e){
        console.warn('[DEVICE] load devices', e);
    }
}
