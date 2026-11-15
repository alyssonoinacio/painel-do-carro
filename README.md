# Jarvis do Carro

Launcher Android para usar um tablet como central do carro, com visual de HUD e um assistente de voz.

- **Reator central**: toque e fale. Comandos do carro funcionam sem internet: "navegar para o shopping",
  "aumenta o volume", "próxima", "pausa", "ligar para 11 99999-9999", "abrir Waze", "que horas são", "bateria".
- **Sem tocar**: em Ajustes, ligue "Acordar ao ouvir “Jarvis”". Diga "Jarvis" (ou "Jarvis, navegar para casa"). Depois de
  uma resposta de conversa ele escuta mais uma vez, sem precisar repetir "Jarvis".
- **Voz**: voz do Gemini pelo servidor (`/v1/audio/speech`), com tom calmo e formal; sem internet, usa a voz do Android.
- **Conversa**: o resto vai para um servidor compatível com a API da OpenAI, como o
  [gemini-balance](https://github.com/snailyp/gemini-balance) ou o [OpenJarvis](https://github.com/open-jarvis/OpenJarvis)
  (`jarvis serve`). Configure em **Ajustes**: endereço, chave e modelo.
- **Módulos**: Navegar (Waze/Maps), Música, Telefone, Apps, Ajustes e Atualizações. Segure um app na lista para
  fixá-lo na doca.
- **Atualização automática** pelas releases deste repositório.
- Responsivo: paisagem (reator à esquerda) e retrato (reator em cima). Android 5.0+, 32 ou 64 bits.

Compilar: `gradle assembleRelease` (JDK 17 + Android SDK 34). Fonte Rajdhani sob a licença OFL (`app/src/main/assets/OFL.txt`).
