package com.mio.tomahawk.ui.web

import java.util.Locale

/** Os componentes das abas web: cabeçalho, anel de progresso, botões, controles segmentados, linha do tempo, console. */
object WebUi {

    fun ring(done: Int, total: Int, tone: String, center: String = if (total > 0) "${done * 100 / total}%" else "—", size: Int = 72): String {
        val pct = if (total <= 0) 0.0 else done * 100.0 / total
        return "<div class=\"ring\" style=\"--rs:${size}px;--rc:var(--$tone)\"><svg viewBox=\"0 0 36 36\">" +
            "<circle class=\"rb\" cx=\"18\" cy=\"18\" r=\"15.9155\"/>" +
            "<circle class=\"rf\" cx=\"18\" cy=\"18\" r=\"15.9155\" stroke-dasharray=\"${"%.1f".format(Locale.ROOT, pct)} 100\"/></svg>" +
            "<div class=\"rt\">$center</div></div>"
    }

    /** Cabeçalho das abas: ícone num ladrilho, sobretítulo, título, linha de apoio, etiquetas e o anel à direita. */
    fun header(icon: String, kicker: String, title: String, subtitle: String?, chips: String = "", side: String = ""): String = buildString {
        append("<header class=\"top\"><div class=\"top-icon\">$icon</div><div class=\"top-body\">")
        append("<div class=\"kicker\">").append(kicker).append("</div>")
        append("<div class=\"top-title\">").append(title).append("</div>")
        subtitle?.let { append("<div class=\"top-sub\">").append(it).append("</div>") }
        if (chips.isNotEmpty()) append("<div class=\"chips\">").append(chips).append("</div>")
        append("</div>")
        if (side.isNotEmpty()) append("<div class=\"top-side\">").append(side).append("</div>")
        append("</header>")
    }

    fun chip(label: String, value: String, tone: String? = null, cmd: String? = null, arg: String? = null): String {
        val attrs = buildString {
            cmd?.let { append(" data-cmd=\"$it\"") }
            arg?.let { append(" data-arg=\"${attr(it)}\"") }
        }
        val cls = listOfNotNull("chip", tone?.let { "c-$it" }, cmd?.let { "clickable" }).joinToString(" ")
        return "<span class=\"$cls\"$attrs><b>$label</b>$value</span>"
    }

    fun button(text: String, cmd: String, kind: String = "", arg: String? = null, enabled: Boolean = true, title: String? = null): String =
        "<button class=\"btn $kind\" data-cmd=\"$cmd\"" + (arg?.let { " data-arg=\"${attr(it)}\"" } ?: "") +
            (title?.let { " title=\"${attr(it)}\"" } ?: "") + (if (enabled) "" else " disabled") + ">$text</button>"

    /** Controle segmentado: escolhe um valor de [options] (valor → rótulo); [send] manda a escolha na hora. */
    fun seg(name: String, options: List<Pair<String, String>>, selected: String?, send: String? = null): String = buildString {
        append("<div class=\"seg\"").append(send?.let { " data-send=\"$it\"" }.orEmpty()).append(">")
        append("<input type=\"hidden\" name=\"$name\" value=\"${attr(selected.orEmpty())}\">")
        options.forEach { (value, label) ->
            append("<button type=\"button\" data-value=\"${attr(value)}\"").append(if (value == selected) " class=\"on\"" else "").append(">$label</button>")
        }
        append("</div>")
    }

    fun section(title: String, extra: String = ""): String = "<div class=\"sec\"><span>$title</span><i class=\"line\"></i>$extra</div>"

    /** Um texto seguro para atributo HTML entre aspas duplas. */
    fun attr(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun script(bridge: String, scrollY: Int): String = """
        (function(){
          var raw = $bridge;
          var tmk = window.tmk = {
            send: function(c, data){ var m = data || {}; m.c = c; raw(JSON.stringify(m)); },
            form: function(el){
              var root = el.closest('[data-form]'); var out = {};
              if (!root) return out;
              root.querySelectorAll('[name]').forEach(function(i){
                if (i.type === 'radio') { if (i.checked) out[i.name] = i.value; }
                else if (i.type === 'checkbox') out[i.name] = i.checked;
                else out[i.name] = i.value;
              });
              return out;
            },
            press: function(el){
              el.classList.remove('pressed'); void el.offsetWidth; el.classList.add('pressed');
              clearTimeout(el._press); el._press = setTimeout(function(){ el.classList.remove('pressed'); }, 500);
            },
            set: function(key, value){ document.querySelectorAll('[data-f="' + key + '"]').forEach(function(e){ e.textContent = value; }); },
            flight: function(s){
              if (!document.getElementById('flight')) return;
              Object.keys(s).forEach(function(k){ tmk.set(k, s[k]); });
              var trail = document.querySelector('[data-f="trail"]'); if (trail) trail.style.display = s.trail ? '' : 'none';
              var detail = document.querySelector('[data-f="detail"]'); if (detail) detail.style.display = s.detail ? '' : 'none';
            },
            log: function(lines){
              var box = document.getElementById('console'); if (!box) return;
              var near = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
              lines.forEach(function(l){
                var row = document.createElement('div'); row.className = 'ln k-' + l.k;
                var t = document.createElement('span'); t.className = 'lt'; t.textContent = l.t;
                var g = document.createElement('span'); g.className = 'lg'; g.textContent = l.g;
                var x = document.createElement('span'); x.className = 'lx'; x.textContent = l.x;
                row.appendChild(t); row.appendChild(g); row.appendChild(x); box.appendChild(row);
              });
              var empty = box.querySelector('.console-empty'); if (empty && lines.length) empty.remove();
              if (near) box.scrollTop = box.scrollHeight;
            }
          };
          document.addEventListener('click', function(e){
            var seg = e.target.closest('.seg button');
            if (seg) {
              e.preventDefault();
              tmk.press(seg);
              var box = seg.closest('.seg');
              box.querySelectorAll('button').forEach(function(b){ b.classList.toggle('on', b === seg); });
              var input = box.querySelector('input[type=hidden]'); if (input) input.value = seg.getAttribute('data-value');
              if (box.getAttribute('data-send')) { var d = tmk.form(box); d.value = seg.getAttribute('data-value'); tmk.send(box.getAttribute('data-send'), d); }
              return;
            }
            var tog = e.target.closest('[data-fold-toggle]');
            var inner = e.target.closest('[data-cmd],a,input,textarea,select');
            if (tog && !(inner && tog.contains(inner))) {
              e.preventDefault();
              if (tog.classList.contains('btn')) tmk.press(tog);
              var box = tog.closest('[data-fold]');
              if (box) tmk.send('fold', { arg: box.getAttribute('data-fold'), folded: box.classList.toggle('folded') });
              return;
            }
            var el = e.target.closest('[data-cmd]');
            if (!el || el.disabled || el.classList.contains('disabled')) return;
            if (e.target.closest('input,textarea,select') && el.tagName !== 'BUTTON') return;
            e.preventDefault();
            tmk.press(el);
            var data = tmk.form(el);
            var arg = el.getAttribute('data-arg'); if (arg !== null) data.arg = arg;
            var line = el.getAttribute('data-line'); if (line !== null) data.line = line;
            tmk.send(el.getAttribute('data-cmd'), data);
          });
          document.addEventListener('change', function(e){
            var el = e.target.closest('[data-change]'); if (!el) return;
            var data = tmk.form(el); data.value = el.value; tmk.send(el.getAttribute('data-change'), data);
          });
          var drafts = {};
          document.addEventListener('input', function(e){
            var el = e.target; var key = el.getAttribute && el.getAttribute('data-draft'); if (!key) return;
            clearTimeout(drafts[key]);
            drafts[key] = setTimeout(function(){ tmk.send('draft', { arg: key, text: el.value }); }, 250);
          });
          document.addEventListener('focusin', function(e){ if (e.target.matches('input:not([type=hidden]),textarea,select')) tmk.send('focus'); });
          document.addEventListener('focusout', function(e){ if (e.target.matches('input:not([type=hidden]),textarea,select')) setTimeout(function(){
            if (!document.activeElement || !document.activeElement.matches('input,textarea,select')) tmk.send('blur');
          }, 60); });
          document.addEventListener('keydown', function(e){
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && e.target.matches('textarea')) {
              var form = e.target.closest('[data-form]'); var go = form && form.querySelector('.btn.primary:not([disabled])');
              if (go) { e.preventDefault(); go.click(); }
            }
          });
          var timer;
          window.addEventListener('scroll', function(){ clearTimeout(timer); timer = setTimeout(function(){ tmk.send('scroll', { y: Math.round(window.scrollY) }); }, 150); });
          window.scrollTo(0, $scrollY);
          var auto = document.querySelector('[data-autofocus]'); if (auto && !auto.value) { /* sem foco automático: evita roubar o foco do editor */ }
          var cons = document.getElementById('console'); if (cons) cons.scrollTop = cons.scrollHeight;
        })();
    """.trimIndent()

    val CSS = """
        .app .wrap{max-width:1100px;padding:14px 14px 48px}
        button{font-family:inherit}
        .top{position:relative;display:flex;gap:14px;align-items:center;padding:16px 18px;border-radius:16px;border:1px solid var(--border);overflow:hidden;
          background:radial-gradient(120% 140% at 0% 0%,var(--accent-bg) 0%,var(--card) 60%)}
        .top::after{content:"";position:absolute;right:-50px;bottom:-70px;width:180px;height:180px;border-radius:50%;background:var(--accent);opacity:.07}
        .top-icon{flex:none;width:48px;height:48px;border-radius:14px;display:flex;align-items:center;justify-content:center;font-size:24px;
          background:var(--bg);border:1px solid var(--border);box-shadow:var(--shadow)}
        .top-body{flex:1;min-width:0;position:relative;z-index:1}
        .kicker{font-size:10.5px;font-weight:800;letter-spacing:.1em;text-transform:uppercase;color:var(--accent-fg)}
        .top-title{font-size:19px;font-weight:800;line-height:1.25;margin-top:2px;overflow-wrap:anywhere}
        .top-sub{color:var(--muted);font-size:12.5px;margin-top:3px}
        .top .chips{margin-top:9px}
        .top-side{flex:none;position:relative;z-index:1;display:flex;flex-direction:column;align-items:center;gap:4px}
        .top-side small{color:var(--muted);font-size:11px}
        .chip.clickable{cursor:pointer} .chip.clickable:hover{border-color:var(--accent)}
        .chip.c-green{border-color:var(--green);color:var(--green-fg)} .chip.c-yellow{border-color:var(--yellow);color:var(--yellow-fg);background:var(--yellow-bg)}
        .chip.c-red{border-color:var(--red);color:var(--red-fg)} .chip.c-blue{border-color:var(--blue);color:var(--blue-fg)}
        .chip.c-orange{border-color:var(--orange);color:var(--orange-fg)} .chip.c-purple{border-color:var(--purple);color:var(--purple-fg)}

        .ring{position:relative;width:var(--rs);height:var(--rs)}
        .ring svg{width:100%;height:100%;transform:rotate(-90deg)}
        .ring circle{fill:none;stroke-width:3.4}
        .ring .rb{stroke:var(--gray-bg)}
        .ring .rf{stroke:var(--rc);stroke-linecap:round;transition:stroke-dasharray .6s}
        .ring .rt{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-weight:800;font-size:15px;color:var(--fg)}

        .sec{display:flex;align-items:center;gap:8px;margin:20px 2px 10px;font-size:11px;font-weight:800;letter-spacing:.09em;text-transform:uppercase;color:var(--muted)}
        .sec > span{flex:none}
        .sec .line{flex:1;height:1px;background:var(--border)}
        .sec .btn{text-transform:none;letter-spacing:0;font-weight:600}

        .btn{display:inline-flex;align-items:center;gap:6px;height:30px;padding:0 13px;border-radius:9px;border:1px solid var(--border);background:var(--bg);
          color:var(--fg);font-size:12.5px;font-weight:600;cursor:pointer;transition:all .15s;white-space:nowrap}
        .btn:hover:not([disabled]){border-color:var(--accent);color:var(--accent-fg);transform:translateY(-1px)}
        .btn.primary{background:linear-gradient(135deg,var(--accent),var(--accent-fg));border-color:transparent;color:#fff;box-shadow:0 2px 10px -2px var(--accent)}
        .btn.primary:hover:not([disabled]){color:#fff;filter:brightness(1.08)}
        .btn.big{height:36px;padding:0 18px;font-size:13.5px;border-radius:11px}
        .btn.danger{border-color:var(--red);color:var(--red-fg)}
        .btn.ghost{background:transparent}
        .btn[disabled]{opacity:.45;cursor:not-allowed;box-shadow:none}
        .btn{position:relative}
        .btn:not(.primary):hover:not([disabled]){background:var(--accent-bg);box-shadow:0 2px 8px -4px var(--accent)}
        .btn.danger:hover:not([disabled]){border-color:var(--red);color:var(--red-fg);background:var(--red-bg);box-shadow:0 2px 8px -4px var(--red)}
        .btn:focus-visible{outline:none;box-shadow:0 0 0 3px var(--accent-bg),0 0 0 1px var(--accent)}
        .btn.btn:active:not([disabled]),.btn.btn.pressed:not([disabled]){transform:translateY(1px) scale(.97);border-color:var(--accent);color:var(--accent-fg);
          background:var(--accent-bg);box-shadow:inset 0 2px 5px rgba(0,0,0,.22);transition-duration:.05s}
        .btn.primary:active:not([disabled]),.btn.primary.pressed:not([disabled]){color:#fff;background:linear-gradient(135deg,var(--accent-fg),var(--accent));
          filter:brightness(.9);box-shadow:inset 0 2px 6px rgba(0,0,0,.35)}
        .btn.danger:active:not([disabled]),.btn.danger.pressed:not([disabled]){border-color:var(--red);color:var(--red-fg);background:var(--red-bg)}
        .btn.pressed::after,.seg button.pressed::after{content:"";position:absolute;inset:-1px;border-radius:inherit;pointer-events:none;animation:press-ring .5s ease-out}
        @keyframes press-ring{from{box-shadow:0 0 0 0 var(--accent)}to{box-shadow:0 0 0 7px transparent}}
        [data-cmd].pressed:not(.btn){animation:press-flash .5s ease-out}
        @keyframes press-flash{from{box-shadow:0 0 0 3px var(--accent)}to{box-shadow:0 0 0 0 transparent}}
        .chip.clickable:active,.st-cmd.clickable:active,.feat:active,.acard:not(.missing):active,.li:active,.run:active,.chart .cb:active{transform:scale(.98)}
        .row-btns{display:flex;flex-wrap:wrap;gap:8px;align-items:center}

        .seg{display:inline-flex;padding:3px;border-radius:10px;background:var(--gray-bg);gap:2px;flex-wrap:wrap}
        .seg button{border:0;background:transparent;color:var(--muted);height:26px;padding:0 11px;border-radius:7px;font-size:12px;font-weight:600;cursor:pointer}
        .seg button.on{background:var(--bg);color:var(--fg);box-shadow:var(--shadow)}
        .seg button{position:relative;transition:background .12s,color .12s,transform .05s}
        .seg button:hover:not(.on){color:var(--fg);background:rgba(127,127,127,.14)}
        .seg button:active,.seg button.pressed{transform:scale(.95)}
        .seg button.on{color:var(--accent-fg);box-shadow:var(--shadow),inset 0 -2px 0 var(--accent)}

        .card2{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:14px 16px;box-shadow:var(--shadow)}
        .card2 + .card2{margin-top:12px}
        .card2 h3{margin:0 0 4px;font-size:14px;display:flex;align-items:center;gap:8px}
        .card2 .desc{color:var(--muted);font-size:12px;margin-bottom:10px}
        .grid2{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(250px,100%),1fr));gap:12px}
        .notice{display:flex;gap:10px;align-items:flex-start;padding:10px 14px;border-radius:12px;border:1px solid;margin-top:12px}
        .notice .ni{font-size:16px;line-height:1.3}
        .notice .nb{flex:1;min-width:0}
        .notice.warn{background:var(--yellow-bg);border-color:var(--yellow);color:var(--yellow-fg)}
        .notice.info{background:var(--blue-bg);border-color:var(--blue);color:var(--blue-fg)}
        .notice.ok{background:var(--green-bg);border-color:var(--green);color:var(--green-fg)}
        .notice.err{background:var(--red-bg);border-color:var(--red);color:var(--red-fg)}
        .notice .btn{margin-top:8px}

        .field{display:flex;flex-direction:column;gap:4px;margin-bottom:12px}
        .field label{font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:var(--muted)}
        .field .hint{font-size:11.5px;color:var(--muted)}
        .field .fh{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}
        .btn.sm{height:24px;padding:0 9px;font-size:11.5px;border-radius:7px}
        .field .hint.bad{color:var(--red-fg)} .field .hint.good{color:var(--green-fg)}
        .in,select.in,textarea.in{width:100%;border:1px solid var(--border);background:var(--bg);color:var(--fg);border-radius:9px;padding:7px 10px;font:13px inherit;outline:none;transition:border .15s,box-shadow .15s}
        .in:focus{border-color:var(--accent);box-shadow:0 0 0 3px var(--accent-bg)}
        textarea.in{min-height:96px;resize:vertical;line-height:1.5;font-family:inherit}
        .in-row{display:flex;gap:8px} .in-row .in{flex:1}
        .cols{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(200px,100%),1fr));gap:0 12px}
        .status-line{font-size:12px;margin-top:8px;color:var(--muted)} .status-line.good{color:var(--green-fg)} .status-line.bad{color:var(--red-fg)}

        .feats{display:flex;gap:8px;overflow-x:auto;padding:2px 2px 6px;scrollbar-width:thin}
        .feat{flex:none;min-width:150px;max-width:230px;padding:9px 12px;border-radius:12px;border:1px solid var(--border);background:var(--card);cursor:pointer;transition:all .15s}
        .feat:hover{border-color:var(--accent);transform:translateY(-1px)}
        .feat.on{border-color:var(--accent);background:linear-gradient(135deg,var(--accent-bg),var(--card));box-shadow:0 0 0 1px var(--accent) inset}
        .feat .fn{font-weight:700;font-size:12.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .feat .fm{color:var(--muted);font-size:11px;margin:2px 0 6px;display:flex;gap:6px;align-items:center}
        .feat .num{font:800 11px "JetBrains Mono",Consolas,monospace;color:var(--accent-fg)}
        .feat.new{border-style:dashed;display:flex;align-items:center;justify-content:center;color:var(--muted);font-weight:700;min-width:120px}

        .stepper{position:relative}
        .st{position:relative;display:flex;gap:12px;padding-bottom:10px}
        .st:not(:last-child)::before{content:"";position:absolute;left:17px;top:38px;bottom:-2px;width:2px;background:var(--border)}
        .st.done:not(:last-child)::before{background:linear-gradient(var(--green),var(--border))}
        .st-node{flex:none;width:36px;height:36px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-weight:800;font-size:13px;
          border:2px solid var(--border);background:var(--bg);color:var(--muted);position:relative;z-index:1;transition:all .2s}
        .st.done .st-node{background:var(--green);border-color:var(--green);color:#fff}
        .st.ready .st-node{border-color:var(--orange);color:var(--orange-fg);background:var(--orange-bg)}
        .st.recommended .st-node{border-color:var(--yellow);color:var(--yellow-fg);background:var(--yellow-bg)}
        .st.partial .st-node{border-color:var(--blue);color:var(--blue-fg);background:var(--blue-bg)}
        .st.unavailable .st-node{border-color:var(--red);color:var(--red-fg)}
        .st.next .st-node{box-shadow:0 0 0 4px var(--orange-bg),0 0 18px -2px var(--orange);animation:pulse 2.4s ease-in-out 3}
        @keyframes pulse{50%{box-shadow:0 0 0 7px var(--orange-bg),0 0 22px 0 var(--orange)}}
        .st-card{flex:1;min-width:0;border:1px solid var(--border);border-radius:13px;background:var(--card);transition:all .15s;overflow:hidden}
        .st-card:hover{border-color:var(--accent)}
        .st.sel .st-card{border-color:var(--accent);box-shadow:0 6px 24px -10px var(--accent)}
        .st.blocked .st-card,.st.unavailable .st-card{opacity:.75}
        .st-head{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:9px 12px;cursor:pointer}
        .st-name{font-weight:750;font-size:13.5px}
        .st-cmd{font:600 11px "JetBrains Mono",Consolas,monospace;color:var(--muted);background:var(--bg);border:1px solid var(--border);border-radius:6px;padding:0 6px}
        .st-head .grow{flex:1}
        .st-reason{padding:0 12px 9px;color:var(--muted);font-size:12px;margin-top:-4px}
        .st-body{border-top:1px dashed var(--border);padding:12px;background:var(--bg)}
        .st-purpose{margin:0 0 10px;font-size:12.5px}
        .st-tools{display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:space-between;margin-top:10px}
        .kbd{font:600 10.5px "JetBrains Mono",Consolas,monospace;border:1px solid var(--border);border-bottom-width:2px;border-radius:5px;padding:0 5px;color:var(--muted)}
        .tags{display:inline-flex;gap:4px;flex-wrap:wrap}
        .tag{font-size:10.5px;font-weight:700;padding:0 7px;border-radius:999px;line-height:18px;background:var(--gray-bg);color:var(--gray-fg)}
        .tag.t-next{background:linear-gradient(135deg,var(--orange),var(--red));color:#fff}

        .flight{position:relative;margin-top:12px;border-radius:14px;border:1px solid var(--orange);padding:12px 14px;overflow:hidden;
          background:linear-gradient(135deg,var(--orange-bg),var(--card) 70%)}
        .flight-top{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
        .flight-badge{display:inline-flex;align-items:center;gap:6px;font-size:10.5px;font-weight:800;letter-spacing:.09em;text-transform:uppercase;color:var(--orange-fg)}
        .dot-live{width:8px;height:8px;border-radius:50%;background:var(--orange);box-shadow:0 0 0 0 var(--orange);animation:live 1.6s infinite}
        @keyframes live{70%{box-shadow:0 0 0 8px transparent}100%{box-shadow:0 0 0 0 transparent}}
        .flight-title{font-weight:800;font-size:14px;flex:1;min-width:140px}
        .flight-step{margin-top:6px;font-weight:600}
        .flight-step::before{content:"➜ ";color:var(--orange-fg)}
        .flight-trail{color:var(--muted);font-size:12px;margin-top:2px}
        .flight-detail{color:var(--muted);font-size:12px;font-style:italic;margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .flight-stats{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
        .fs{padding:4px 10px;border-radius:9px;background:var(--bg);border:1px solid var(--border);font-size:12px}
        .fs b{font-size:14px;margin-right:4px;font-variant-numeric:tabular-nums}
        .pulse-bar{height:4px;border-radius:4px;background:var(--gray-bg);margin-top:10px;overflow:hidden;position:relative}
        .pulse-bar::after{content:"";position:absolute;top:0;bottom:0;width:35%;border-radius:4px;background:linear-gradient(90deg,transparent,var(--orange),transparent);animation:sweep 1.8s linear infinite}
        @keyframes sweep{from{left:-35%}to{left:100%}}

        .console{margin-top:10px;background:#0D1117;color:#C9D1D9;border-radius:12px;border:1px solid #30363D;padding:10px 12px;max-height:320px;overflow:auto;
          font:12px/1.55 "JetBrains Mono",Consolas,monospace}
        .console .ln{display:flex;gap:8px} .console .lt{color:#6E7681;flex:none} .console .lg{flex:none;width:18px;text-align:center}
        .console .lx{white-space:pre-wrap;word-break:break-word;min-width:0}
        .console .k-TOOL .lg{color:#58A6FF} .console .k-TOOL .lx{color:#79C0FF}
        .console .k-TEXT .lg{color:#D2A8FF} .console .k-ERROR .lg,.console .k-ERROR .lx{color:#FF7B72}
        .console .k-RESULT .lg,.console .k-RESULT .lx{color:#56D364} .console .k-INFO .lg{color:#E3B341}
        .console-empty{color:#6E7681;font-style:italic}

        .acard{position:relative;display:flex;flex-direction:column;gap:6px;padding:13px 14px;border-radius:14px;border:1px solid var(--border);background:var(--card);
          cursor:pointer;transition:all .15s;box-shadow:var(--shadow);overflow:hidden}
        .acard::before{content:"";position:absolute;left:0;top:0;right:0;height:3px;background:var(--t,var(--accent))}
        .acard:hover{transform:translateY(-2px);box-shadow:0 8px 24px -12px var(--t,var(--accent));border-color:var(--t,var(--accent))}
        .acard.missing{border-style:dashed;cursor:default;background:transparent}
        .acard.missing::before{display:none}
        .acard.missing:hover{transform:none;box-shadow:none}
        .ac-top{display:flex;align-items:center;gap:8px}
        .ac-icon{font-size:18px}
        .ac-name{font-weight:800;flex:1;min-width:0}
        .ac-file{font:11px "JetBrains Mono",Consolas,monospace;color:var(--muted)}
        .ac-metrics{display:flex;flex-wrap:wrap;gap:5px}
        .ac-big{font-size:22px;font-weight:800;color:var(--t-fg,var(--fg))}
        .ac-foot{color:var(--muted);font-size:11.5px}

        .list{border:1px solid var(--border);border-radius:13px;background:var(--card);overflow:hidden}
        .li{display:flex;gap:10px;align-items:center;padding:8px 12px;border-bottom:1px dashed var(--border);cursor:pointer;transition:background .12s}
        .li:last-child{border-bottom:none}
        .li:hover{background:var(--bg)}
        .li .grow{flex:1;min-width:0}
        .li .meta{color:var(--muted);font-size:11.5px}
        .li .ttl{font-weight:650;overflow-wrap:anywhere}
        .li .bar{width:120px;flex:none}

        .runs-day{margin:16px 2px 6px;font-size:11px;font-weight:800;color:var(--muted);letter-spacing:.06em;text-transform:uppercase}
        .run{display:flex;gap:12px;align-items:flex-start;padding:10px 12px;border-radius:12px;border:1px solid var(--border);background:var(--card);cursor:pointer;transition:all .15s;margin-bottom:8px}
        .run:hover{border-color:var(--rc);transform:translateX(2px)}
        .run-icon{flex:none;width:30px;height:30px;border-radius:9px;display:flex;align-items:center;justify-content:center;font-size:14px;font-weight:800;background:var(--rbg);color:var(--rc)}
        .run-body{flex:1;min-width:0}
        .run-title{font-weight:750;display:flex;gap:8px;align-items:center;flex-wrap:wrap}
        .run-meta{color:var(--muted);font-size:11.5px;margin-top:2px;display:flex;gap:10px;flex-wrap:wrap}
        .run-err{color:var(--red-fg);font-size:12px;margin-top:4px}
        .r-ok{--rc:var(--green);--rbg:var(--green-bg)} .r-failed{--rc:var(--red);--rbg:var(--red-bg)}
        .r-cancelled{--rc:var(--gray);--rbg:var(--gray-bg)} .r-terminal{--rc:var(--purple);--rbg:var(--purple-bg)}

        .chart{display:flex;align-items:flex-end;gap:4px;height:90px;padding:10px 12px 6px;border:1px solid var(--border);border-radius:13px;background:var(--card)}
        .chart .cb{flex:1;min-width:6px;max-width:26px;border-radius:5px 5px 2px 2px;background:var(--rc);opacity:.85;transition:opacity .15s;cursor:pointer;min-height:4px}
        .chart .cb:hover{opacity:1}
        .chart-legend{display:flex;justify-content:space-between;color:var(--muted);font-size:11px;margin:4px 4px 0}

        .empty2{text-align:center;padding:30px 20px;border:1px dashed var(--border);border-radius:16px;background:var(--card);margin-top:14px}
        .empty2 > .big{font-size:42px;line-height:1.2}
        .empty2 h3{margin:8px 0 4px;font-size:16px}
        .empty2 p{color:var(--muted);margin:0 auto 14px;max-width:520px}
        .cmdline{display:inline-block;margin:6px 0 14px;font:12px "JetBrains Mono",Consolas,monospace;background:var(--code-bg);border:1px solid var(--border);border-radius:8px;padding:6px 10px;
          max-width:100%;overflow-wrap:anywhere}
        .setup-box{display:inline-flex;flex-direction:column;align-items:center;gap:10px;max-width:100%;padding:12px 16px;border:1px solid var(--border);
          border-radius:12px;background:var(--bg);box-shadow:var(--shadow)}
        .setup-box .cmdline{margin:0}
        .card2 .setup-box{display:flex;align-items:stretch;margin-top:8px}
        .card2 .setup-box .row-btns{justify-content:flex-start}
        .setup-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;justify-content:center}
        .card2 .setup-row{justify-content:flex-start}
        .setup-label{font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:var(--muted)}
        .row-btns.center{justify-content:center}

        .caps{display:grid;gap:6px;margin:0;padding:0;list-style:none}
        .caps li{display:flex;gap:8px;align-items:flex-start;font-size:12.5px}
        .caps .ck{flex:none;width:18px;height:18px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:800}
        .caps .on{background:var(--green-bg);color:var(--green-fg)} .caps .off{background:var(--gray-bg);color:var(--muted)}
        .status-big{display:inline-flex;align-items:center;gap:8px;padding:6px 12px;border-radius:999px;font-weight:800;font-size:12.5px;border:1px solid}
        .status-big.good{background:var(--green-bg);color:var(--green-fg);border-color:var(--green)}
        .status-big.warn{background:var(--yellow-bg);color:var(--yellow-fg);border-color:var(--yellow)}
        .status-big.bad{background:var(--gray-bg);color:var(--muted);border-color:var(--border)}
        .fold-ic{flex:none;width:18px;height:18px;display:inline-flex;align-items:center;justify-content:center;border-radius:5px;color:var(--muted);transition:transform .15s,background .15s}
        .fold-ic::before{content:"▾";font-size:12px;line-height:1}
        [data-fold-toggle]:hover > .fold-ic{background:var(--gray-bg);color:var(--fg)}
        .folded > [data-fold-toggle] > .fold-ic{transform:rotate(-90deg)}
        .rpr{margin-bottom:14px;padding:0;overflow:hidden}
        .rpr-head{display:flex;gap:8px;align-items:flex-start;flex-wrap:wrap;padding:12px 14px;cursor:pointer;user-select:none}
        .rpr-head > .fold-ic{margin-top:1px}
        .rpr-head .grow{flex:1;min-width:200px}
        .rpr-title{font-weight:800;font-size:14px;overflow-wrap:anywhere}
        .rpr-title .num{font:800 12px "JetBrains Mono",Consolas,monospace;color:var(--accent-fg)}
        .rpr-body{padding:2px 14px 14px;border-top:1px solid var(--border)}
        .rpr.folded > .rpr-body{display:none}
        .rthread{border:1px solid var(--border);border-radius:12px;background:var(--bg);margin-top:12px;overflow:hidden;position:relative}
        .rthread::before{content:"";position:absolute;left:0;top:0;bottom:0;width:3px;background:var(--border)}
        .rthread.wait::before{background:var(--yellow)} .rthread.new::before{background:var(--purple)} .rthread.done::before{background:var(--green)}
        .rthread.new{box-shadow:0 4px 18px -12px var(--purple)}
        .rthread.done:not(:hover){opacity:.85}
        .rt-head{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:8px 12px 8px 10px;cursor:pointer;user-select:none;background:var(--card)}
        .rthread:not(.folded) > .rt-head{border-bottom:1px solid var(--border)}
        .rt-kind{font-weight:750;font-size:12.5px}
        .rt-time,.rt-count{color:var(--muted);font-size:11.5px;white-space:nowrap}
        .rt-peek{display:flex;align-items:center;gap:8px;padding:0 12px 9px 36px;margin-top:-2px;background:var(--card);cursor:pointer;font-size:12px;min-width:0}
        .rt-peek .rc-av{width:20px;height:20px;font-size:10px}
        .rt-peek b{flex:none}
        .rt-peek .txt{color:var(--muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0}
        .rthread:not(.folded) > .rt-peek,.rthread.folded > .rt-body{display:none}
        .rt-body{padding:12px 12px 12px 14px}
        .rt-reply{margin-top:12px;padding-top:12px;border-top:1px dashed var(--border)}
        .st-cmd.clickable{cursor:pointer} .st-cmd.clickable:hover{border-color:var(--accent);color:var(--accent-fg)}
        .tag.t-new{background:var(--purple-bg);color:var(--purple-fg)} .tag.t-wait{background:var(--yellow-bg);color:var(--yellow-fg)}
        .tag.t-done{background:var(--green-bg);color:var(--green-fg)}

        .code{border:1px solid var(--border);border-radius:10px;overflow:hidden;background:var(--ed-bg,var(--code-bg));margin-bottom:12px}
        .code-head{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:5px 8px 5px 10px;background:var(--card);border-bottom:1px solid var(--border);font-size:12px}
        .code-head .grow{flex:1}
        .code-file{font:12px var(--ed-font,"JetBrains Mono",Consolas,monospace);overflow-wrap:anywhere;cursor:pointer}
        .code-file .dir{color:var(--muted)} .code-file:hover b{color:var(--accent-fg);text-decoration:underline}
        .code-meta{color:var(--muted);font-size:11.5px}
        .code-stat{display:inline-flex;gap:6px;font:700 11.5px "JetBrains Mono",Consolas,monospace}
        .code-stat .a{color:var(--green-fg)} .code-stat .d{color:var(--red-fg)}
        .code .if-open{display:inline} .code .if-folded{display:none}
        .code.folded .if-open{display:none} .code.folded .if-folded{display:inline}
        .code-body{overflow-x:auto;scrollbar-width:thin}
        .code table{border-collapse:collapse;min-width:100%;font:var(--ed-size,12px)/1.55 var(--ed-font,"JetBrains Mono",Consolas,monospace);color:var(--ed-fg,var(--fg))}
        .code td{padding:0;vertical-align:top}
        .code .gt{position:sticky;left:0;z-index:1;white-space:nowrap;background:var(--ed-gutter,var(--ed-bg,var(--code-bg)));color:var(--ed-ln,var(--muted));
          border-right:1px solid var(--border);padding:0 6px 0 4px;user-select:none;font-size:.92em}
        .code .gt span{display:inline-block;min-width:3.2ch;text-align:right;padding-left:6px}
        .code .sg{width:1.6em;text-align:center;user-select:none;color:var(--muted)}
        .code .cd{white-space:pre;padding-right:16px}
        .code tr.add > td.cd,.code tr.add > td.sg{background:var(--ed-add,var(--green-bg))}
        .code tr.del > td.cd,.code tr.del > td.sg{background:var(--ed-del,var(--red-bg))}
        .code tr.add > td.gt{box-shadow:inset -3px 0 0 var(--green)} .code tr.del > td.gt{box-shadow:inset -3px 0 0 var(--red)}
        .code tr.add > td.sg{color:var(--green-fg)} .code tr.del > td.sg{color:var(--red-fg)}
        .code tr.sep > td,.code tr.note > td{color:var(--muted);font-style:italic;background:var(--blue-bg)}
        .code tr.focus > td.gt{color:var(--ed-ln-on,var(--fg));font-weight:700;box-shadow:inset 3px 0 0 var(--accent)}
        .code tr.focus:not(.add):not(.del) > td.cd,.code tr.focus:not(.add):not(.del) > td.sg{background:var(--ed-caret,var(--accent-bg))}
        .code.folded tr.far{display:none}
        .code tr.fold-row{display:none;cursor:pointer}
        .code.folded tr.fold-row{display:table-row}
        .code tr.fold-row > td{color:var(--muted);background:var(--code-bg);font-family:inherit;font-size:11.5px;padding-top:2px;padding-bottom:2px}
        .code tr.fold-row:hover > td.cd{color:var(--accent-fg)}

        .rcs{position:relative}
        .rcs::before{content:"";position:absolute;left:13px;top:14px;bottom:14px;width:2px;background:var(--border)}
        .rc{position:relative;display:flex;gap:10px;align-items:flex-start}
        .rc + .rc{margin-top:10px}
        .rc-av{flex:none;position:relative;z-index:1;width:28px;height:28px;border-radius:50%;display:inline-flex;align-items:center;justify-content:center;
          font-weight:800;font-size:12px;color:#fff;background:var(--av,var(--accent));box-shadow:0 0 0 3px var(--bg)}
        .rc-main{flex:1;min-width:0;border:1px solid var(--border);border-radius:10px;background:var(--bg);overflow:hidden}
        .rc.me .rc-main{border-color:var(--accent)} .rc.new .rc-main{border-color:var(--purple);box-shadow:0 0 0 1px var(--purple-bg)}
        .rc-head{display:flex;align-items:center;gap:6px;flex-wrap:wrap;padding:4px 10px;font-size:12px;color:var(--muted);background:var(--card);border-bottom:1px solid var(--border)}
        .rc.me .rc-head{background:var(--accent-bg)} .rc-head b{color:var(--fg)} .rc-head .grow{flex:1}
        .rc-time{font-size:11.5px;white-space:nowrap}
        .rc-link{cursor:pointer;padding:0 4px;border-radius:4px} .rc-link:hover{background:var(--gray-bg);color:var(--accent-fg)}
        .rc-body{font-size:12.5px;overflow-wrap:anywhere;padding:8px 12px}
        .rc-body > :first-child{margin-top:0} .rc-body > :last-child{margin-bottom:0}
        .rc-body p{margin:4px 0}
        .rc-body pre{overflow-x:auto;background:var(--ed-bg,var(--code-bg));border:1px solid var(--border);border-radius:8px;padding:8px 10px;font:var(--ed-size,12px)/1.5 var(--ed-font,"JetBrains Mono",Consolas,monospace)}
        .rc-body code{font-family:var(--ed-font,"JetBrains Mono",Consolas,monospace)}
        @media (max-width:520px){ .top{flex-wrap:wrap} .top-side{flex-direction:row} .li .bar{width:70px} }
    """.trimIndent()
}
