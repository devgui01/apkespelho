# Espelho Casanova - espelha tela do celular no PC via navegador

APK simples: ao instalar e tocar em "Iniciar", transmite a tela via MJPEG.
No PC (mesma rede/WiFi) abra: http://IP-DO-CELULAR:8080

## Como gerar o APK (precisa Android Studio, não dá pra compilar aqui sem SDK)

1. Instale o Android Studio + SDK 34 + JDK 17
2. Abra a pasta `espelho-tela` no Android Studio
3. Aguarde sincronizar o Gradle
4. Menu Build > Build APK(s) > Build debug APK
5. APK sai em: `app/build/outputs/apk/debug/app-debug.apk`
6. Copie para o celular, instale, permita "exibir sobre outros apps" se pedir
7. No celular: toque Iniciar > confirme "Iniciar agora" na permissão de captura
8. No PC: abra o endereço mostrado na tela (ex: http://10.12.50.79:8080)

## Notas
- Só vídeo, ~10 fps, sem áudio (de propósito).
- Precisa estar na mesma rede (casanova/industria). Se o PC via cabo não alcançar o WiFi do celular, não abre.
- Se der tela preta em app de banco, é proteção do Android (FLAG_SECURE), normal.
- Porta 8080. Se já estiver em uso, mude `PORT` em ScreenMirrorService.kt.
