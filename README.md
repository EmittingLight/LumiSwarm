# LumiSwarm
Generative firefly galaxy — interactive lightfield on JavaFX.

## Features
- 🎇 Fireflies with glow & trails (T)
- 🌌 Galaxy swirl (G), adjustable gravity [`[` `]`]
- ✨ Constellations (C, +/- radius)
- 💥 Shockwaves (Space / Shift+Space)
- ⚡ Lightning bolts (L) + screen flash
- 🎨 Palette drift (P), bloom (B)
- 💾 PNG snapshot (S)

## Controls
- **Mouse**: LMB attract, RMB repel, wheel = add/remove particles  
- **Keys**: `G` galaxy • `C` constellations • `T` trails • `B` bloom • `P` palette  
  `[`/`]` gravity • `+`/`-` link radius • `Space` shockwave • `L` lightning • `R` palette • `S` save

## Run
```bash
mvn -q -DskipTests javafx:run
