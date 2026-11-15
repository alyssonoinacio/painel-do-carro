# Jarvis do Carro

Launcher Android para usar um tablet como central do carro, com visual de HUD e um assistente de voz.

- **Reator central**: toque e fale. Comandos do carro funcionam sem internet: "navegar para o shopping",
  "aumenta o volume", "próxima", "pausa", "ligar para 11 99999-9999", "abrir Waze", "que horas são", "bateria".
- **Conversa**: o resto vai para um servidor compatível com a API da OpenAI, como o
  [OpenJarvis](https://github.com/open-jarvis/OpenJarvis) (`jarvis serve`). Configure em **Ajustes**: endereço,
  chave (`OPENJARVIS_API_KEY`) e modelo.
- **Módulos**: Navegar (Waze/Maps), Música, Telefone, Apps, Ajustes e Atualizações. Segure um app na lista para
  fixá-lo na doca.
- **Atualização automática** pelas releases deste repositório.
- Responsivo: paisagem (reator à esquerda) e retrato (reator em cima). Android 5.0+, 32 ou 64 bits.

Compilar: `gradle assembleRelease` (JDK 17 + Android SDK 34). Fonte Rajdhani sob a licença OFL (`app/src/main/assets/OFL.txt`).
