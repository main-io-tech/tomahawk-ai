package com.mio.tomahawk.view

/**
 * Os diagramas da página: o bloco interativo que o Mermaid desenha (com zoom, arrastar, ajustar, tela cheia, ver
 * e copiar o código), o tema do Mermaid nas cores da IDE e o estilo das tabelas, árvores, camadas, fluxos e
 * endpoints.
 */
object Diagrams {

    private val BOX_CHARS = Regex("""[┌┐└┘─│┬┴┤┼╔╗╚╝═║▶►▼▲◀]""")
    private val ASCII_BOX = Regex("""\+[-=]{3,}\+""")
    private val ARROWS = Regex("""-->|->|──>|=>|→|↓|↑|←""")

    /** Um bloco de código desenhado à mão: caixas com traços de desenho ou várias linhas com setas. */
    fun isAscii(code: String): Boolean {
        val lines = code.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return false
        return lines.count { BOX_CHARS.containsMatchIn(it) } >= 2 || lines.count { ASCII_BOX.containsMatchIn(it) } >= 2 ||
            lines.count { ARROWS.containsMatchIn(it) } >= 2 && lines.any { it.contains('[') || it.contains('(') || it.contains('|') }
    }

    fun title(code: String): String {
        val first = code.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("%%") && !it.startsWith("---") && !it.startsWith("title") }.orEmpty()
        val keyword = first.substringBefore(' ').lowercase()
        return when {
            keyword == "flowchart" || keyword == "graph" -> "🔀 Fluxograma"
            keyword == "sequencediagram" -> "🔁 Diagrama de sequência"
            keyword == "erdiagram" -> "🗃️ Diagrama entidade-relacionamento"
            keyword.startsWith("statediagram") -> "🚦 Diagrama de estados"
            keyword == "classdiagram" -> "🧩 Diagrama de classes"
            keyword == "journey" -> "🧭 Jornada do usuário"
            keyword == "gantt" -> "📅 Cronograma"
            keyword == "mindmap" -> "🧠 Mapa mental"
            keyword.startsWith("c4") -> "🏛️ Arquitetura (C4)"
            keyword.startsWith("architecture") -> "🏛️ Arquitetura"
            keyword.startsWith("block") -> "🧱 Diagrama de blocos"
            keyword == "pie" -> "🥧 Gráfico de pizza"
            keyword == "timeline" -> "🕒 Linha do tempo"
            keyword == "gitgraph" -> "🌿 Fluxo de branches"
            keyword.startsWith("quadrant") -> "🔲 Quadrantes"
            keyword.startsWith("sankey") -> "🌊 Fluxo (Sankey)"
            keyword.startsWith("xychart") -> "📈 Gráfico"
            else -> "📐 Diagrama"
        }
    }

    fun block(code: String, title: String): String = buildString {
        append("<div class=\"diagram\"><div class=\"dg-bar\"><span class=\"dg-title\">").append(esc(title)).append("</span>")
        append("<span class=\"dg-tools\"><span class=\"dg-zoom\">100%</span>")
        append("<button data-act=\"out\" title=\"Afastar\">−</button><button data-act=\"in\" title=\"Aproximar\">+</button>")
        append("<button data-act=\"fit\" title=\"Ajustar à largura (ou duplo clique)\">⤢</button>")
        append("<button data-act=\"full\" title=\"Tela cheia (Esc fecha)\">⛶</button>")
        append("<button data-act=\"code\" title=\"Ver o código do diagrama\">&lt;/&gt;</button>")
        append("<button data-act=\"copy\" title=\"Copiar o código\">⧉</button></span></div>")
        append("<div class=\"dg-canvas\" title=\"Arraste para mover · Ctrl + roda do mouse para zoom\"><div class=\"dg-inner\"><div class=\"dg-loading\">Desenhando…</div></div></div>")
        append("<div class=\"dg-error\"></div><pre class=\"dg-src\">").append(esc(code)).append("</pre></div>")
    }

    /** A biblioteca do Mermaid (MIT), embutida na página só quando ela tem diagramas. */
    val mermaidScript: String by lazy {
        Diagrams::class.java.getResourceAsStream("/diagram/mermaid.min.js")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?.replace("</script", "<\\/script").orEmpty()
    }

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun script(theme: HtmlTheme): String {
        val d = theme.dark
        fun c(light: String, dark: String) = if (d) dark else light
        val vars = """{
          darkMode: $d, background: '${theme.background}', fontSize: '14px',
          fontFamily: '"Segoe UI", Inter, -apple-system, Roboto, sans-serif',
          primaryColor: '${c("#EEF4FF", "#12304F")}', primaryTextColor: '${c("#1F2328", "#E6EDF3")}', primaryBorderColor: '${c("#0969DA", "#2F81F7")}',
          secondaryColor: '${c("#FBEFFF", "#3A2350")}', secondaryBorderColor: '${c("#8250DF", "#A371F7")}', secondaryTextColor: '${c("#1F2328", "#E6EDF3")}',
          tertiaryColor: '${c("#F6F8FA", "#2B2D30")}', tertiaryBorderColor: '${c("#D8DEE4", "#393B40")}', tertiaryTextColor: '${c("#1F2328", "#E6EDF3")}',
          lineColor: '${c("#8C959F", "#6E7681")}', textColor: '${c("#1F2328", "#DFE1E5")}', mainBkg: '${c("#EEF4FF", "#12304F")}',
          nodeBorder: '${c("#0969DA", "#2F81F7")}', clusterBkg: '${c("#F6F8FA", "#26282C")}', clusterBorder: '${c("#D8DEE4", "#393B40")}',
          edgeLabelBackground: '${theme.background}', titleColor: '${c("#1F2328", "#E6EDF3")}',
          attributeBackgroundColorOdd: '${c("#FFFFFF", "#1E1F22")}', attributeBackgroundColorEven: '${c("#F6F8FA", "#26282C")}',
          actorBkg: '${c("#EEF4FF", "#12304F")}', actorBorder: '${c("#0969DA", "#2F81F7")}', actorTextColor: '${c("#1F2328", "#E6EDF3")}',
          signalColor: '${c("#57606A", "#9198A1")}', signalTextColor: '${c("#1F2328", "#DFE1E5")}', labelBoxBkgColor: '${c("#EEF4FF", "#12304F")}',
          noteBkgColor: '${c("#FFF8C5", "#4A3D12")}', noteBorderColor: '${c("#D4A72C", "#BB8009")}', noteTextColor: '${c("#1F2328", "#E6EDF3")}',
          activationBkgColor: '${c("#DDF4FF", "#1C3A5E")}', sequenceNumberColor: '#FFFFFF',
          labelColor: '${c("#1F2328", "#E6EDF3")}', altBackground: '${c("#F6F8FA", "#26282C")}',
          cScale0: '${c("#DDF4FF", "#12304F")}', cScale1: '${c("#FBEFFF", "#3A2350")}', cScale2: '${c("#DAFBE1", "#13361F")}',
          cScale3: '${c("#FFF1E5", "#4A2A12")}', cScale4: '${c("#FFEFF7", "#4A1A35")}', cScale5: '${c("#D8F5F7", "#10373A")}'
        }"""
        return """
        (function(){
          var boxes = Array.prototype.slice.call(document.querySelectorAll('.diagram'));
          if (!boxes.length) return;
          var send = window.__tomahawkSend || function(){};
          if (!window.mermaid) {
            boxes.forEach(function(b){ b.classList.add('failed', 'show-code'); b.querySelector('.dg-error').textContent = 'A biblioteca de diagramas não carregou.'; });
            return;
          }
          mermaid.initialize({
            startOnLoad: false, securityLevel: 'strict', theme: 'base', themeVariables: $vars,
            flowchart: { useMaxWidth: false, curve: 'basis', padding: 14, nodeSpacing: 46, rankSpacing: 56, htmlLabels: true },
            er: { useMaxWidth: false, entityPadding: 16, fontSize: 13, minEntityWidth: 120 },
            sequence: { useMaxWidth: false, mirrorActors: false, showSequenceNumbers: true, boxMargin: 12 },
            state: { useMaxWidth: false }, class: { useMaxWidth: false }, journey: { useMaxWidth: false },
            gantt: { useMaxWidth: false }, mindmap: { useMaxWidth: false }, timeline: { useMaxWidth: false }
          });
          var fits = [];
          boxes.forEach(function(box, i){
            var state = { s: 1, x: 16, y: 16 };
            var canvas = box.querySelector('.dg-canvas');
            var inner = box.querySelector('.dg-inner');
            var label = box.querySelector('.dg-zoom');
            var src = box.querySelector('.dg-src').textContent;
            function apply(){ inner.style.transform = 'translate(' + state.x + 'px,' + state.y + 'px) scale(' + state.s + ')'; label.textContent = Math.round(state.s * 100) + '%'; }
            function size(){
              var svg = inner.querySelector('svg'); if (!svg) return null;
              var vb = svg.viewBox && svg.viewBox.baseVal;
              var r = svg.getBoundingClientRect();
              return { w: (vb && vb.width) || r.width / state.s, h: (vb && vb.height) || r.height / state.s };
            }
            function fit(){
              var d = size(); if (!d) return;
              var full = box.classList.contains('full');
              var cw = Math.max(120, canvas.clientWidth - 32);
              var maxH = full ? Math.max(120, canvas.clientHeight - 32) : Math.max(240, Math.min(window.innerHeight * 0.8, 820)) - 32;
              var sw = cw / d.w, sh = maxH / d.h;
              var s = Math.min(full ? 2 : 1.15, sw);
              if (full) s = Math.min(s, sh);
              else if (d.h * s > maxH && Math.min(sw, sh) >= 0.45) s = Math.min(sw, sh);
              state.s = Math.max(0.18, s);
              if (!full) canvas.style.height = Math.min(d.h * state.s + 32, maxH + 32) + 'px';
              state.x = Math.max(16, (canvas.clientWidth - d.w * state.s) / 2);
              state.y = full ? Math.max(16, (canvas.clientHeight - d.h * state.s) / 2) : 16;
              apply();
            }
            fits.push(fit);
            function zoom(f, cx, cy){
              var r = canvas.getBoundingClientRect();
              if (cx == null) { cx = r.width / 2; cy = r.height / 2; }
              var ns = Math.min(5, Math.max(0.12, state.s * f));
              state.x = cx - (cx - state.x) * ns / state.s;
              state.y = cy - (cy - state.y) * ns / state.s;
              state.s = ns; apply();
            }
            box.querySelector('.dg-tools').addEventListener('click', function(e){
              var b = e.target.closest('button'); if (!b) return;
              var act = b.getAttribute('data-act');
              if (act === 'in') zoom(1.2);
              else if (act === 'out') zoom(1 / 1.2);
              else if (act === 'fit') fit();
              else if (act === 'full') {
                box.classList.toggle('full');
                document.body.classList.toggle('has-full', !!document.querySelector('.diagram.full'));
                setTimeout(fit, 40);
              }
              else if (act === 'code') box.classList.toggle('show-code');
              else if (act === 'copy') { send('copy:' + src); b.classList.add('ok'); setTimeout(function(){ b.classList.remove('ok'); }, 1200); }
            });
            canvas.addEventListener('wheel', function(e){
              if (!e.ctrlKey && !box.classList.contains('full')) return;
              e.preventDefault();
              var r = canvas.getBoundingClientRect();
              zoom(e.deltaY < 0 ? 1.12 : 1 / 1.12, e.clientX - r.left, e.clientY - r.top);
            }, { passive: false });
            var drag = null;
            canvas.addEventListener('mousedown', function(e){
              if (e.button !== 0) return;
              drag = { x: e.clientX - state.x, y: e.clientY - state.y };
              canvas.classList.add('grabbing'); e.preventDefault();
            });
            window.addEventListener('mousemove', function(e){ if (!drag) return; state.x = e.clientX - drag.x; state.y = e.clientY - drag.y; apply(); });
            window.addEventListener('mouseup', function(){ drag = null; canvas.classList.remove('grabbing'); });
            canvas.addEventListener('dblclick', fit);
            mermaid.render('tmk-dg-' + i, src).then(function(result){
              inner.innerHTML = result.svg;
              var svg = inner.querySelector('svg'); var d = size();
              if (svg && d) { svg.setAttribute('width', d.w); svg.setAttribute('height', d.h); svg.style.maxWidth = 'none'; }
              box.classList.add('ready'); fit();
            }).catch(function(err){
              box.classList.add('failed', 'show-code'); inner.innerHTML = '';
              box.querySelector('.dg-error').textContent = 'Não foi possível desenhar este diagrama: ' + ((err && (err.message || err.str)) || err);
              var stray = document.getElementById('dtmk-dg-' + i); if (stray) stray.remove();
            });
          });
          document.addEventListener('keydown', function(e){
            if (e.key !== 'Escape') return;
            document.querySelectorAll('.diagram.full').forEach(function(b){ b.classList.remove('full'); });
            document.body.classList.remove('has-full');
            fits.forEach(function(f){ f(); });
          });
          var timer;
          window.addEventListener('resize', function(){ clearTimeout(timer); timer = setTimeout(function(){ fits.forEach(function(f){ f(); }); }, 150); });
        })();
        """.trimIndent()
    }

    val CSS = """
        .diagram{margin:14px 0;border:1px solid var(--border);border-radius:14px;background:var(--card);overflow:hidden;box-shadow:var(--shadow)}
        .dg-bar{display:flex;align-items:center;gap:8px;padding:7px 10px 7px 14px;border-bottom:1px solid var(--border);background:linear-gradient(90deg,var(--accent-bg),transparent 65%);flex-wrap:wrap}
        .dg-title{font-weight:700;font-size:12.5px;flex:1;min-width:120px}
        .dg-tools{display:flex;gap:4px;align-items:center}
        .dg-tools button{border:1px solid var(--border);background:var(--bg);color:var(--fg);border-radius:7px;min-width:28px;height:26px;padding:0 7px;cursor:pointer;font:600 12px/1 "Segoe UI",sans-serif;transition:all .12s}
        .dg-tools button:hover{border-color:var(--accent);color:var(--accent-fg)}
        .dg-tools button.ok{background:var(--green-bg);color:var(--green-fg);border-color:var(--green)}
        .dg-zoom{font-size:11px;color:var(--muted);min-width:40px;text-align:right;font-variant-numeric:tabular-nums;margin-right:2px}
        .dg-canvas{position:relative;overflow:hidden;height:240px;cursor:grab;background-color:var(--bg);background-image:radial-gradient(var(--border) 1px,transparent 1px);background-size:18px 18px}
        .dg-canvas.grabbing{cursor:grabbing}
        .dg-inner{position:absolute;left:0;top:0;transform-origin:0 0;transition:transform .05s linear}
        .dg-canvas.grabbing .dg-inner{transition:none}
        .dg-loading{padding:24px;color:var(--muted)}
        .dg-src{display:none;margin:0;border-top:1px solid var(--border);padding:12px 14px;background:var(--code-bg);font:12px/1.5 "JetBrains Mono",Consolas,monospace;white-space:pre;overflow:auto;max-height:320px}
        .diagram.show-code .dg-src{display:block}
        .dg-error{display:none;padding:10px 14px;background:var(--red-bg);color:var(--red-fg);font-size:12px}
        .diagram.failed .dg-error{display:block} .diagram.failed .dg-canvas{display:none}
        .diagram.full{position:fixed;inset:10px;z-index:1000;margin:0;display:flex;flex-direction:column;box-shadow:0 12px 48px rgba(0,0,0,.4)}
        .diagram.full .dg-canvas{flex:1;height:auto!important}
        body.has-full{overflow:hidden}

        .tables{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(330px,100%),1fr));gap:14px;align-items:start}
        .table-card{background:var(--card);border:1px solid var(--border);border-radius:14px;overflow:hidden;box-shadow:var(--shadow);scroll-margin-top:12px;transition:box-shadow .15s}
        .table-card:hover{box-shadow:0 4px 18px rgba(0,0,0,.12)}
        .table-head{display:flex;align-items:center;gap:8px;padding:10px 14px;background:linear-gradient(135deg,var(--t-bg),transparent 85%);border-bottom:2px solid var(--t);color:var(--t-fg)}
        .ticon{display:flex}
        .tname{font:800 14.5px "JetBrains Mono",Consolas,monospace;flex:1;color:var(--fg);min-width:0;overflow-wrap:anywhere}
        .tcount{font-size:11px;color:var(--muted);white-space:nowrap}
        .table-desc{padding:8px 14px 2px;color:var(--muted);font-size:12px}
        .table-rows{padding:4px 0}
        .trow{display:grid;grid-template-columns:18px minmax(0,1fr) auto;gap:3px 8px;align-items:center;padding:6px 14px;border-bottom:1px dashed var(--border)}
        .trow:has(+ .tnote){border-bottom:none;padding-bottom:2px}
        .trow:hover,.trow:hover + .tnote{background:var(--bg)}
        .trow .flags{grid-column:2 / -1;display:flex;flex-wrap:wrap;gap:4px}
        .trow .flags:empty{display:none}
        .key{display:flex;color:var(--muted)} .trow.pk .key{color:var(--yellow)} .trow.fk .key{color:var(--blue)}
        .fname{font:600 12.5px "JetBrains Mono",Consolas,monospace;min-width:0;overflow-wrap:anywhere}
        .trow.pk .fname{color:var(--yellow-fg)}
        .ftype{font:600 11px "JetBrains Mono",Consolas,monospace;padding:1px 8px;border-radius:6px;background:var(--gray-bg);color:var(--gray-fg);justify-self:end;white-space:nowrap;max-width:160px;overflow:hidden;text-overflow:ellipsis}
        .ty-text{background:var(--blue-bg);color:var(--blue-fg)} .ty-num{background:var(--orange-bg);color:var(--orange-fg)}
        .ty-bool{background:var(--purple-bg);color:var(--purple-fg)} .ty-date{background:var(--teal-bg);color:var(--teal-fg)}
        .ty-enum{background:var(--pink-bg);color:var(--pink-fg)} .ty-json{background:var(--yellow-bg);color:var(--yellow-fg)}
        .ty-list{background:var(--green-bg);color:var(--green-fg)} .ty-ref{background:var(--purple-bg);color:var(--purple-fg)}
        .flag{font:700 10px/16px "Segoe UI",sans-serif;padding:0 6px;border-radius:5px;border:1px solid var(--border);color:var(--muted);text-decoration:none;white-space:nowrap}
        .flag.pkf{background:var(--yellow-bg);color:var(--yellow-fg);border-color:var(--yellow)}
        .flag.fkf{background:var(--blue-bg);color:var(--blue-fg);border-color:var(--blue)}
        .flag.req{color:var(--red-fg);border-color:var(--red)} .flag.opt{border-style:dashed} .flag.def{color:var(--teal-fg);border-color:var(--teal)}
        .tnote{padding:0 14px 6px 40px;color:var(--muted);font-size:12px;border-bottom:1px dashed var(--border)}
        .tnote.enum{border-bottom:none;padding-bottom:2px}
        .ev{display:inline-block;margin:2px 4px 0 0;padding:0 7px;border-radius:999px;background:var(--pink-bg);color:var(--pink-fg);font:600 11px "JetBrains Mono",Consolas,monospace}
        .table-foot{padding:4px 14px 12px;background:var(--bg);border-top:1px solid var(--border)}
        .foot-title{font-size:10.5px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:var(--muted);margin:10px 0 6px}
        .rels{display:flex;flex-wrap:wrap;gap:6px}
        .rel{display:inline-flex;align-items:center;gap:6px;padding:3px 10px 3px 4px;border-radius:999px;border:1px solid var(--border);background:var(--card);color:var(--fg);text-decoration:none;font-size:12px}
        .rel:hover{border-color:var(--t)}
        .card-badge{font:700 10px "JetBrains Mono",Consolas,monospace;padding:1px 6px;border-radius:999px;background:var(--t-bg);color:var(--t-fg)}
        .rules{margin:0;padding-left:18px;font-size:12px}
        .table-foot .diagram{margin:6px 0 0}

        .ftree{border:1px solid var(--border);border-radius:14px;background:var(--card);margin:12px 0;overflow:hidden;box-shadow:var(--shadow)}
        .ftree-head{display:flex;gap:8px;align-items:center;padding:9px 14px;border-bottom:1px solid var(--border);font-weight:700;background:linear-gradient(90deg,var(--accent-bg),transparent 65%);flex-wrap:wrap}
        .ftree-head span:first-child{flex:1}
        .ftree ul{list-style:none;margin:0;padding-left:0}
        .ftree ul.ftree-root{padding:8px 12px 10px}
        .ftree ul ul{border-left:1px dashed var(--border);margin-left:9px;padding-left:12px}
        .ftree li{margin:1px 0}
        .fnode{display:flex;align-items:center;gap:6px;padding:2px 6px;border-radius:6px;flex-wrap:wrap}
        .fnode:hover{background:var(--bg)}
        .ficon{width:18px;text-align:center;flex:none}
        .fname-tree{font:12.5px "JetBrains Mono",Consolas,monospace}
        .ftree li.dir > .fnode .fname-tree{font-weight:700}
        .fnote{color:var(--muted);font-size:12px;font-style:italic}
        .fnote::before{content:"— "}

        .arch{display:grid;grid-template-columns:minmax(0,1fr) minmax(200px,280px);gap:14px;margin:12px 0}
        .arch-main{display:flex;flex-direction:column}
        .layer{display:flex;gap:12px;align-items:flex-start;padding:12px 14px;border-radius:14px;border:1px solid var(--t);background:linear-gradient(135deg,var(--t-bg),var(--card) 85%);box-shadow:var(--shadow)}
        .layer-icon{font-size:20px;width:38px;height:38px;border-radius:10px;display:flex;align-items:center;justify-content:center;background:var(--bg);border:1px solid var(--border);flex:none}
        .layer-body{min-width:0;flex:1}
        .layer-title{font-size:10.5px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;color:var(--t-fg)}
        .layer-value{margin-top:2px}
        .deps,.mods{display:flex;flex-wrap:wrap;gap:5px;margin-top:6px}
        .dep{padding:1px 9px;border-radius:999px;background:var(--bg);border:1px solid var(--border);font-size:12px}
        .mod{display:inline-block;padding:1px 8px;border-radius:6px;background:var(--bg);border:1px dashed var(--t,var(--border));font:12px "JetBrains Mono",Consolas,monospace;color:var(--t-fg,var(--fg))}
        .arch-arrow{height:28px;display:flex;justify-content:center}
        .arch-arrow span{width:2px;background:linear-gradient(var(--border),var(--accent));position:relative}
        .arch-arrow span::after{content:"";position:absolute;bottom:-2px;left:50%;transform:translateX(-50%);border:6px solid transparent;border-top-color:var(--accent);border-bottom:0}
        .arch-side{display:flex;flex-direction:column;gap:10px}
        .side-card{padding:10px 12px;border-radius:12px;border:1px solid var(--border);background:var(--card);font-size:12.5px;box-shadow:var(--shadow)}
        .side-title{font-size:10.5px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;color:var(--muted);margin-bottom:3px}

        .flows{display:flex;flex-direction:column;gap:10px;margin:10px 0 16px}
        .flow{display:flex;gap:10px;align-items:flex-start}
        .flow-n{flex:none;width:26px;height:26px;border-radius:50%;background:var(--accent-bg);color:var(--accent-fg);border:1px solid var(--accent);font-weight:800;font-size:12px;display:flex;align-items:center;justify-content:center;margin-top:10px}
        .flow-steps{flex:1;min-width:0;display:flex;flex-wrap:wrap;align-items:center;row-gap:8px}
        .step{flex:1 1 170px;min-width:0;padding:8px 12px;border-radius:10px;border:1px solid var(--sb);background:var(--sbg);box-shadow:var(--shadow)}
        .step .sk{display:block;font-size:10px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;color:var(--sfg);margin-bottom:2px}
        .s-given{--sb:var(--blue);--sbg:var(--blue-bg);--sfg:var(--blue-fg)} .s-when{--sb:var(--purple);--sbg:var(--purple-bg);--sfg:var(--purple-fg)}
        .s-then{--sb:var(--green);--sbg:var(--green-bg);--sfg:var(--green-fg)} .s-and{--sb:var(--border);--sbg:var(--card);--sfg:var(--muted)}
        .flow-arrow{flex:none;width:24px;height:2px;background:var(--gray);position:relative;margin:0 2px}
        .flow-arrow::after{content:"";position:absolute;right:-2px;top:-5px;border:6px solid transparent;border-left-color:var(--gray);border-right:0}

        .ascii{margin:12px 0;border:1px solid var(--border);border-radius:12px;overflow:hidden;background:var(--card);box-shadow:var(--shadow)}
        .ascii-head{padding:7px 12px;font-weight:700;font-size:12px;border-bottom:1px solid var(--border);background:linear-gradient(90deg,var(--accent-bg),transparent 65%)}
        .doc .ascii pre{margin:0;border:0;border-radius:0;background:var(--bg);line-height:1.25}

        .eps{display:flex;flex-direction:column;gap:8px}
        .ep{border:1px solid var(--border);border-radius:12px;background:var(--card);overflow:hidden;border-left:4px solid var(--mc);box-shadow:var(--shadow)}
        .ep summary{display:flex;align-items:center;gap:10px;padding:9px 12px;cursor:pointer;list-style:none;flex-wrap:wrap}
        .ep summary::-webkit-details-marker{display:none}
        .ep[open] summary{border-bottom:1px dashed var(--border)}
        .method{font:800 11px "JetBrains Mono",Consolas,monospace;padding:3px 8px;border-radius:6px;background:var(--mc);color:#fff;min-width:60px;text-align:center}
        .ep-path{font:600 13px "JetBrains Mono",Consolas,monospace;overflow-wrap:anywhere}
        .param{color:var(--orange-fg);background:var(--orange-bg);border-radius:4px;padding:0 3px}
        .ep-sum{color:var(--muted);flex:1;min-width:140px}
        .codes{display:flex;gap:4px;flex-wrap:wrap}
        .code{font:700 10.5px "JetBrains Mono",Consolas,monospace;padding:1px 6px;border-radius:5px;background:var(--gray-bg);color:var(--gray-fg)}
        .code.c2{background:var(--green-bg);color:var(--green-fg)} .code.c3{background:var(--blue-bg);color:var(--blue-fg)}
        .code.c4{background:var(--orange-bg);color:var(--orange-fg)} .code.c5{background:var(--red-bg);color:var(--red-fg)}
        .ep-body{padding:2px 14px 12px}
        .params{display:flex;flex-wrap:wrap;gap:6px}
        .pchip{display:inline-flex;gap:6px;align-items:center;padding:2px 10px;border-radius:999px;border:1px solid var(--border);background:var(--bg);font-size:12px;color:var(--fg);text-decoration:none}
        .pchip b{font-family:"JetBrains Mono",Consolas,monospace}
        .resps{display:grid;gap:5px}
        .resp{display:flex;gap:8px;align-items:center;font-size:12.5px}
        .m-get{--mc:var(--green)} .m-post{--mc:var(--blue)} .m-put{--mc:var(--orange)} .m-patch{--mc:var(--teal)} .m-delete{--mc:var(--red)}
        .m-head,.m-options{--mc:var(--gray)}
        details.source{margin:16px 0;border:1px solid var(--border);border-radius:10px;background:var(--card)}
        details.source summary{cursor:pointer;padding:8px 12px;color:var(--muted);font-weight:600}
        details.source pre{margin:0;border:0;border-top:1px solid var(--border);border-radius:0 0 10px 10px;max-height:480px;overflow:auto;padding:12px 14px;background:var(--code-bg);font:12px/1.45 "JetBrains Mono",Consolas,monospace}

        .quickstart .doc ol{list-style:none;counter-reset:qs;padding-left:0}
        .quickstart .doc ol > li{counter-increment:qs;position:relative;padding:4px 0 14px 44px;margin:0}
        .quickstart .doc ol > li::before{content:counter(qs);position:absolute;left:0;top:1px;width:28px;height:28px;border-radius:50%;background:var(--accent-bg);color:var(--accent-fg);border:2px solid var(--accent);display:flex;align-items:center;justify-content:center;font-weight:800;font-size:12px}
        .quickstart .doc ol > li::after{content:"";position:absolute;left:15px;top:33px;bottom:2px;width:2px;background:var(--border)}
        .quickstart .doc ol > li:last-child::after{display:none}
        @media (max-width:760px){ .arch{grid-template-columns:minmax(0,1fr)} }
    """.trimIndent()
}
