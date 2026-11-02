Crea una app Android nativa en Kotlin con Jetpack Compose: un asistente de voz personal en español que funcione 100% offline y privado. Ningún dato personal debe salir del dispositivo.

Dispositivo: Samsung Galaxy S24 Ultra (12 GB RAM, Snapdragon 8 Gen 3).

Funcionamiento:
1. Pantalla oscura con un círculo animado en el centro (estilo holograma azul) que es el botón principal y reacciona cuando escucha y cuando habla.
2. Al tocarlo, escucha usando SpeechRecognizer en modo on-device (createOnDeviceSpeechRecognizer, idioma es-ES). Si no está disponible, avísame en pantalla.
3. Procesa el texto con un modelo LLM local usando Google AI Edge (la API de inferencia LLM on-device más reciente y recomendada). Usa el modelo Gemma más adecuado para móvil. Revisa la documentación actual para elegir la librería y versión correctas, y explícame qué archivo de modelo debo descargar y cómo copiarlo al móvil.
4. Lee la respuesta en voz alta con TextToSpeech en español.
5. Muestra en pantalla la conversación.
6. Mantén el historial de la conversación para que recuerde el contexto.
7. Carga el modelo en un hilo aparte y muestra un indicador mientras se prepara.

Personalidad (system prompt): "Eres un asistente personal ingenioso, eficiente y con un toque de humor. Respondes en español, de forma breve, porque tus respuestas se leen en voz alta."

Estructura el código por módulos (voz, cerebro, habla) para que después pueda:
- añadir un modo híbrido que use un servidor Ollama en mi PC cuando esté disponible en la Wi-Fi,
- añadir un disparador externo desde un reloj Huawei por Wear Engine,
- añadir lectura de notificaciones, calendario y un resumen matutino.

No uses ninguna API en la nube. Pide solo los permisos necesarios (micrófono).
Al terminar, explícame paso a paso cómo compilar e instalar en mi móvil conectado por USB.