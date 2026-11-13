# TVOS-SKIN — brief do fork

Objetivo: elevar a fidelidade ao tvOS do launcher do CleanYourTV, sem perder o que ele ja faz bem
(vidro unico que desliza com spring, fundo escuro, topo limpo).

Alvo: TCL 65C6K (G08, MediaTek MT9689, Android 14 / Google TV 14, 2,4 GB RAM, painel 4K).

## Nao-objetivo

- Nao mexer em `examples/`, `tools/disable.sh`, listas de debloat, `PROMPT.md`.
- Nao traduzir. Traducao e outro fork/PR.
- Nao trocar a arquitetura (Views + Java). Nada de Compose, nada de dependencia nova.

## Restricoes tecnicas

- `compileSdk 34`, `targetSdk 34`, `minSdk 26`, Gradle 8.9, JDK 17.
- **Sem blur por frame.** `RenderEffect` nao entra. Blur de fundo = crop do wallpaper pre-borrado,
  em bitmap cacheado de baixa resolucao, desenhado dentro do dock.
- `GlassView` ja usa `LAYER_TYPE_HARDWARE` porque o path de clip/glow e software-rendered no
  MT5895. Qualquer efeito novo tem que caber no mesmo orcamento de frame.
- Toda feature atras de flag em `launcher/app/src/main/res/values/config.xml`, **default off**:
  `tile_tilt`, `dock_blur`, `hero_art`.

## Pontos de entrada no codigo

| Arquivo | Papel |
|---|---|
| `GlassView.java` | slab de vidro que segue o foco (rim, sheen, glow, estilos GLASS_*) |
| `Motion.java` | springs e modo de movimento reduzido |
| `DockSlots.java`, `DockSlotsTest` | posicoes do dock |
| `DockCorners.java` equivalente em `DockCornersTest` | raio de canto do dock |
| `HeroArt.java`, `TopFade.java` | arte/fade do topo |
| `WallpaperPlayer.java`, `ClockDream.java` | wallpaper em video e screensaver |
| `Config.java`, `ThemeProvider.java` | flags e temas |

## Escopo por prioridade

1. **tile_tilt** — tile focado escala ~1.05 e inclina 2–4° seguindo a direcao do D-pad, com
   especular que anda junto. `setCameraDistance` + `rotationX/Y` + spring proprio.
   Teste: `MotionTest` + um novo `TileTiltTest` (matematica de eixo/limite, sem Android).
2. **dock_blur** — crop pre-borrado do wallpaper atras do dock, com saturacao levemente elevada.
   Teste: `DockOpacityTest`/`GlassStyleTest` estendidos.
3. **hero_art** — imagem por app em `hero/<package>.jpg`, crossfade ao focar, vinheta no rodape.
   Sem imagem: cai no gradiente atual.

## Definicao de pronto (por feature)

- flag em `config.xml`, default off, documentada no `Config.java`;
- teste unitario rodando em `./gradlew test` (sem emulador);
- nenhum frame descartado perceptivel no D-pad segurado (validar na TV);
- screenshot antes/depois em `docs/tvos-skin/`.

## Build e teste

```bash
cd launcher
./gradlew test          # unit tests, sem TV
./gradlew assembleDebug # APK
```

Instalar na TV (quando ela chegar): `TV_HOST=<ip> launcher/build-and-install.sh`
(exige `tools/venv` com `requirements.txt`; JDK 17; Android SDK 34 + build-tools 34).

## Regras de convivencia com upstream

- `origin` = Gathin23/cleanyourtv, branch de trabalho `tvos-skin`.
- Mudancas pequenas, em commits separados por feature, para virar PR depois.
- Nada de reformatacao de codigo alheio. Diff cirurgico.
