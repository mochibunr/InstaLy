# Third-Party Notices

## AndroidLiquidGlass / Backdrop

InstaLy's optional **Floating iOS Bottom Navbar** includes a native Android View adaptation of the rounded-rectangle liquid-glass refraction shader logic from [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass).

- Project: AndroidLiquidGlass (Backdrop)
- Copyright: Copyright 2025 Kyant
- License: Apache License 2.0
- Upstream source used as reference: `backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt`
- Component behavior used as reference: `LiquidBottomTab.kt` and `LiquidBottomTabs.kt`
- InstaLy modification: the Compose backdrop pipeline was adapted to an injected native Android View pipeline that captures Instagram's native content strip, then applies vibrancy, blur and Android RuntimeShader refraction while preserving Instagram's own navigation views and event handlers.

The repository root `LICENSE` contains the Apache License 2.0 text.
