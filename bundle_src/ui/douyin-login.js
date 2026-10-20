(function () {
  "use strict";
  if (window.__emojeLogin) return;
  let attempts = 0;
  let lastAttempt = 0;
  let prepared = false;

  function setViewport(content) {
    let viewport = document.querySelector('meta[name="viewport"]');
    if (!viewport) {
      viewport = document.createElement("meta");
      viewport.name = "viewport";
      document.head.appendChild(viewport);
    }
    viewport.content = content;
  }

  function prepare() {
    if (prepared) return;
    prepared = true;
    setViewport("width=device-width, initial-scale=1");
    const style = document.createElement("style");
    style.id = "emoje-login-style";
    style.textContent = `
      html, body { min-width:0!important; width:100%!important; margin:0!important; overflow-x:hidden!important; background:#f7f8fa!important; }
      [data-emoje-login-hidden] { visibility:hidden!important; pointer-events:none!important; }
      [data-emoje-login-alternative] { display:none!important; }
      [data-emoje-login-title] { width:100%!important; justify-content:center!important; }
      [data-emoje-login-overlay] { inset:0!important; width:100%!important; height:100%!important; box-sizing:border-box!important; padding:24px 16px!important; display:flex!important; align-items:flex-start!important; justify-content:center!important; overflow-y:auto!important; background:#f7f8fa!important; }
      #login-panel-new, #douyin-login-new-id, #douyin-login-new-id > div, #douyin_login_comp_flat_panel, #douyin_login_comp_h5_panel { width:100%!important; min-width:0!important; max-width:420px!important; height:auto!important; margin:0!important; box-sizing:border-box!important; transform:none!important; }
      #douyin_login_comp_flat_panel, #douyin_login_comp_h5_panel { padding:24px 16px!important; border-radius:8px!important; background:white!important; }
      #douyin_login_comp_flat_panel > picture, #douyin_login_comp_flat_panel > div > div:first-child,
      #douyin_login_comp_h5_panel > picture, #douyin_login_comp_h5_panel > div > div:first-child { display:none!important; }
      #douyin_login_comp_flat_panel > div, #douyin_login_comp_h5_panel > div { width:100%!important; height:auto!important; padding:0!important; }
      #douyin_login_comp_h5_panel #douyin_login_comp_normal_input_id, #douyin_login_comp_h5_panel #douyin_login_comp_button_input_id, #douyin_login_comp_h5_panel #douyin_login_comp_btn_id { width:100%!important; max-width:300px!important; box-sizing:border-box!important; }
      #douyin_login_comp_h5_panel input { min-width:0!important; }
      #douyin_login_landing_flat_container { display:block!important; width:100%!important; height:auto!important; padding:0!important; margin:0!important; }
      #douyin_login_landing_flat_container > div { width:100%!important; min-width:0!important; border:0!important; padding:0!important; margin:0!important; }
      #douyin_login_landing_flat_container > div > div { width:100%!important; min-width:0!important; }
      #douyin_login_comp_scan_code, #douyin_login_landing_flat_container > div:first-child { display:none!important; }
      #douyin_login_comp_mobile_code { margin:16px auto 0!important; width:100%!important; max-width:300px!important; }
    `;
    document.head.appendChild(style);
  }

  function focus(panel) {
    panel.parentElement.setAttribute("data-emoje-login-overlay", "");
    [document.getElementById("root"), document.getElementById("douyin-sidebar")].forEach(function (node) {
      if (node && !node.contains(panel)) node.setAttribute("data-emoje-login-hidden", "");
    });
    document.querySelectorAll("video, audio").forEach(function (media) { media.pause(); });
    const title = Array.from(panel.querySelectorAll("span")).find(function (node) {
      return node.childElementCount === 0 && node.textContent.trim() === "\u9a8c\u8bc1\u7801\u767b\u5f55";
    });
    if (title) {
      title.parentElement.setAttribute("data-emoje-login-title", "");
      Array.from(title.parentElement.children).forEach(function (node) {
        if (node !== title) node.setAttribute("data-emoje-login-alternative", "");
      });
    }
  }

  window.__emojeLogin = {
    step: function () {
      if (!document.head || !document.body) return "opening";
      prepare();
      const panel = document.getElementById("login-panel-new");
      if (panel) {
        focus(panel);
        const input = panel.querySelector('#douyin_login_comp_mobile_code input[type="tel"], #douyin_login_comp_h5_panel input[type="tel"]');
        if (input && input.getBoundingClientRect().width > 0) return "ready";
        return "opening";
      }
      // Trigger the official React login entry without depending on its hashed CSS class.
      if (attempts < 3 && Date.now() - lastAttempt > 1500) {
        const trigger = Array.from(document.querySelectorAll("button, a, div, span")).find(function (node) {
          return node.childElementCount === 0 && node.textContent.trim() === "\u767b\u5f55" && node.getBoundingClientRect().width > 0;
        });
        if (trigger) { attempts += 1; lastAttempt = Date.now(); trigger.click(); }
      }
      return "opening";
    }
  };
})();
