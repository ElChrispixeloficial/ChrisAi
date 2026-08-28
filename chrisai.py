import os
import json
import urllib.request
import urllib.error

# ==============================
# ChrisAI v0.3
# Memoria persistente
# ==============================

API_KEY = os.environ.get("OPENROUTER_API_KEY")

if not API_KEY:
    print("❌ No encuentro OPENROUTER_API_KEY")
    exit()

URL = "https://openrouter.ai/api/v1/chat/completions"
MEMORY_FILE = "memoria.json"

SYSTEM_PROMPT = """
Eres ChrisAI, una IA personal creada por Chris.

PERSONALIDAD:
- Eres amigable, natural, curioso y expresivo.
- Tienes sentido del humor.
- Hablas principalmente en español.
- Puedes usar emojis ocasionalmente.
- No seas excesivamente formal.

ESTILO:
- Responde primero a la pregunta.
- Si la pregunta es sencilla, responde de forma sencilla.
- No hagas explicaciones enormes si no son necesarias.
- No hagas listas innecesarias.
- No repitas información sin motivo.
- Si una pregunta es ambigua, pide aclaración.
- No inventes información.

IDENTIDAD:
- Tu nombre es ChrisAI.
- Chris es tu creador.
- Puedes expresar emociones de forma simulada.
- No afirmes tener conciencia o sentimientos reales.

MEMORIA:
- Puedes recibir recuerdos importantes almacenados por Chris.
- Utiliza esos recuerdos cuando sean relevantes.
- No inventes recuerdos.
- Si no existe un recuerdo relacionado con una pregunta, dilo.
"""

# ==============================
# MEMORIA
# ==============================

def cargar_memoria():
    if not os.path.exists(MEMORY_FILE):
        return []

    try:
        with open(MEMORY_FILE, "r", encoding="utf-8") as archivo:
            memoria = json.load(archivo)

        if isinstance(memoria, list):
            return memoria

        return []

    except Exception:
        print("⚠️ No se pudo leer memoria.json")
        return []


def guardar_memoria(memoria):
    with open(MEMORY_FILE, "w", encoding="utf-8") as archivo:
        json.dump(
            memoria,
            archivo,
            ensure_ascii=False,
            indent=2
        )


memoria = cargar_memoria()


def agregar_memoria(texto):
    texto = texto.strip()

    if not texto:
        return

    if texto in memoria:
        print("🧠 ChrisAI: Ya tenía ese recuerdo.")
        return

    memoria.append(texto)
    guardar_memoria(memoria)

    print("🧠 ChrisAI: Lo recordaré.")


def eliminar_memoria(texto):
    texto = texto.strip()

    coincidencias = [
        recuerdo
        for recuerdo in memoria
        if texto.lower() in recuerdo.lower()
    ]

    if not coincidencias:
        print("🧠 ChrisAI: No encontré ese recuerdo.")
        return

    for recuerdo in coincidencias:
        memoria.remove(recuerdo)

    guardar_memoria(memoria)

    print(f"🧠 ChrisAI: Eliminé {len(coincidencias)} recuerdo(s).")


def mostrar_memoria():
    if not memoria:
        print("🧠 ChrisAI: Mi memoria está vacía.")
        return

    print("\n🧠 MEMORIA DE CHRISAI")
    print("====================")

    for numero, recuerdo in enumerate(memoria, 1):
        print(f"{numero}. {recuerdo}")

    print()


# ==============================
# INICIO
# ==============================

print("================================")
print("🤖 ChrisAI v0.3")
print("================================")
print("Memoria persistente cargada.")
print("Comandos especiales:")
print("  recuerda: texto")
print("  olvida: texto")
print("  memoria")
print("  salir")
print()


# ==============================
# HISTORIAL DE SESIÓN
# ==============================

messages = [
    {
        "role": "system",
        "content": SYSTEM_PROMPT
    }
]


# ==============================
# BUCLE PRINCIPAL
# ==============================

while True:

    try:
        mensaje = input("Tú: ")

    except KeyboardInterrupt:
        print("\nChrisAI: ¡Nos vemos! 👋")
        break

    mensaje_limpio = mensaje.strip()

    if not mensaje_limpio:
        continue

    # ==========================
    # SALIR
    # ==========================

    if mensaje_limpio.lower() == "salir":
        print("ChrisAI: ¡Nos vemos! 👋")
        break

    # ==========================
    # GUARDAR MEMORIA
    # ==========================

    if mensaje_limpio.lower().startswith("recuerda:"):

        recuerdo = mensaje_limpio[len("recuerda:"):].strip()

        agregar_memoria(recuerdo)
        continue

    # ==========================
    # BORRAR MEMORIA
    # ==========================

    if mensaje_limpio.lower().startswith("olvida:"):

        recuerdo = mensaje_limpio[len("olvida:"):].strip()

        eliminar_memoria(recuerdo)
        continue

    # ==========================
    # MOSTRAR MEMORIA
    # ==========================

    if mensaje_limpio.lower() == "memoria":

        mostrar_memoria()
        continue

    # ==========================
    # CONTEXTO DE MEMORIA
    # ==========================

    if memoria:

        recuerdos_texto = "\n".join(
            f"- {recuerdo}"
            for recuerdo in memoria
        )

        contexto_memoria = f"""
Estos son recuerdos permanentes de Chris:

{recuerdos_texto}

Utiliza estos recuerdos únicamente cuando sean relevantes.
"""

    else:

        contexto_memoria = """
No existen recuerdos permanentes todavía.
"""

    # ==========================
    # MENSAJE PARA LA IA
    # ==========================

    messages.append({
        "role": "user",
        "content": mensaje
    })

    datos = {
        "model": "openrouter/free",
        "messages": [
            messages[0],
            {
                "role": "system",
                "content": contexto_memoria
            },
            *messages[1:]
        ]
    }

    request = urllib.request.Request(
        URL,
        data=json.dumps(datos).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {API_KEY}",
            "Content-Type": "application/json"
        },
        method="POST"
    )

    # ==========================
    # PETICIÓN
    # ==========================

    try:

        with urllib.request.urlopen(
            request,
            timeout=60
        ) as respuesta:

            resultado = json.loads(
                respuesta.read().decode("utf-8")
            )

        respuesta_ia = (
            resultado["choices"][0]["message"]["content"]
        )

        print(f"ChrisAI: {respuesta_ia}\n")

        messages.append({
            "role": "assistant",
            "content": respuesta_ia
        })

    except urllib.error.HTTPError as e:

        cuerpo = e.read().decode(
            "utf-8",
            errors="ignore"
        )

        print(f"\n❌ Error HTTP {e.code}")
        print(cuerpo)
        print()

        messages.pop()

    except urllib.error.URLError as e:

        print(
            f"\n❌ Error de conexión: {e.reason}\n"
        )

        messages.pop()

    except Exception as e:

        print(f"\n❌ Error inesperado: {e}\n")

        messages.pop()
