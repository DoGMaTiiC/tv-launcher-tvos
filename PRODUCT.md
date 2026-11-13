# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Android Views (Java) + Gradle 8.9, minSdk 26, compileSdk/targetSdk 34. Sem Compose e sem
biblioteca de design nova: a UI e desenhada em Views custom (GlassView, Motion) sobre o
launcher open-source CleanYourTV (MIT), fork em `tvos-skin`.

## Users

Um unico morador (o dono), na sala, a 2,5-3,5 m da tela, com controle remoto de TV (D-pad,
sem ponteiro e sem toque). Uso principal: escolher um app de streaming e voltar ao que estava
assistindo. Sessao curta, pouca paciencia para navegar.

## Product Purpose

Substituir a tela inicial do Google TV por uma tela rapida, sem anuncio e sem linha
patrocinada, com a sensacao de profundidade e foco do tvOS da Apple. Sucesso = Home abre
instantaneo, o foco e sempre obvio, e nada na tela tenta vender alguma coisa.

## Positioning

O launcher e o unico item da TV que o fabricante nao controla: nao pede conta, nao coleta,
nao empurra conteudo, e e reversivel (o launcher de fabrica continua instalado). Diferente de
um tema, ele e o proprio Home.

## Operating Context

TCL 65C6K (G08, MediaTek MT9689, Android 14 / Google TV 14, 2,4 GB de RAM, painel 4K).
Instalado por ADB, com `AUTO_START` liberado no app-op (sem isso o TclAppBoot mata o launcher
no boot). O launcher de fabrica permanece como reserva. TV chega entre 20 e 24 de novembro.

## Capabilities and Constraints

- Flags de feature em `config.xml`, default off: `tile_tilt`, `dock_blur`, `hero_art`.
- Sem blur por frame (`RenderEffect` proibido): dock blur sai de um crop pre-borrado.
- Orcamento de frame duro: o path de clip/glow ja e software-rendered em parte da familia
  MT589x; qualquer efeito novo precisa caber no mesmo custo.
- Sem acesso a API privilegiada: nao existe "continuar assistindo" entre apps; no maximo
  deep link por app.
- Decisao de nao dar root na TV (perderia Widevine L1 e o 4K).

## Brand Commitments

Aparencia derivada dos principios publicos da HIG de tvOS (nao e clone de marca): preto
absoluto, vidro fosco, tipografia Outfit (ja licenciada no repo, OFL), squircle e parallax
discreto. Nao usar logo, icone ou asset da Apple.

## Evidence on Hand

- `design/DESIGN.md` — tokens normativos (cores, tipografia, raios, espacamento, componentes)
- `design/tokens.json` — export DTCG
- `design/TVOS-RULES.md` — regras HIG destiladas com numeros
- `TVOS-SKIN.md` — brief tecnico do fork
- Capturas: `docs/` do upstream e `refs/` do repo `tv-c6k`

## Product Principles

1. O foco e o produto: todo pixel existe para deixar claro onde ele esta.
2. Movimento confirma, nunca decora.
3. Menos elementos, maiores: a tela e vista de longe.
4. Nada na tela pede atencao que o dono nao pediu.
5. Cada efeito tem preco de frame, e o preco e pago antes de entrar.

## Accessibility & Inclusion

Texto de corpo >= 29px, label >= 25px, contraste AA, safe area de 60px, alvos de foco grandes
(>= 250x150 equivalentes) e nenhuma acao dependente de gesto multi-dedo.
