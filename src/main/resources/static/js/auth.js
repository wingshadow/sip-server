/* 登录态辅助：业务页面引用（置于 common.js 之前），login.html 不引用 */

(function(){
    const LOGIN_PAGE='/login.html';

    const originalFetch=window.fetch;

    /* 后端返回 401（登录态失效/被踢）时统一回登录页，
       避免停留在拿不到数据的页面 */
    window.fetch=function(input,init){
        return originalFetch.apply(this,arguments).then(resp=>{
            if(resp.status===401){
                const url=typeof input==='string'
                    ?input
                    :(input&&input.url)||'';

                if(!url.includes('/api/login')){
                    location.replace(LOGIN_PAGE+'?next='+
                        encodeURIComponent(location.pathname+location.search));
                }
            }

            return resp;
        });
    };

    /* 退出登录：通知后端注销 token 并清除 Cookie，再回登录页 */
    window.logout=function(){
        originalFetch('/api/logout',{method:'POST',cache:'no-store'})
            .catch(()=>{})
            .finally(()=>{
                location.replace(LOGIN_PAGE);
            });
    };
})();
