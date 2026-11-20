# Autoclicker per Yarn Loop

Bot che gioca da solo a **Yarn Loop** su Android. Non tocca a caso: ad ogni turno fa uno
screenshot del telefono, capisce la situazione e tocca il rocchetto giusto.

Come ragiona:

1. **Disegno** – guarda i blocchi di lana sul bordo del disegno, cioè quelli che i rocchetti
   sul nastro possono raggiungere (da sopra, sotto, sinistra e destra), e li conta per colore.
2. **Nastro** – conta i rocchetti che stanno già girando, per non mandarne troppi dello stesso colore.
3. **Slot** – se un rocchetto negli slot di attesa ha un colore che serve, lo rimette in gioco per primo
   (così gli slot non si riempiono).
4. **Griglia** – altrimenti tocca il rocchetto in prima fila col colore più richiesto.
5. **Sblocco** – se nessun colore utile è in prima fila, toglie il rocchetto che ne copre uno utile,
   ma solo se ci sono abbastanza slot liberi.

Non tocca mai la barra dei bonus in basso (quelli che costano monete).

## Cosa serve

- Un PC (Windows / Mac / Linux) con **Python 3.9+** e **ADB**
  ([Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)),
  **oppure** solo il telefono con **Termux** (vedi sotto).
- Sul telefono: *Impostazioni → Info sul telefono → tocca 7 volte "Numero build"*, poi in
  *Opzioni sviluppatore* attiva **Debug USB** (e su Xiaomi anche **Debug USB (impostazioni di sicurezza)**,
  altrimenti i tocchi non funzionano).

```bash
pip install -r requirements.txt
adb devices          # il telefono deve comparire come "device"
```

## Uso

Apri il gioco su un livello, poi:

```bash
python -m yarnloop_bot run --dry-run   # prova: stampa cosa farebbe, senza toccare
python -m yarnloop_bot run             # gioca davvero (Ctrl+C per fermare)
```

Opzioni utili:

| Comando | Cosa fa |
|---|---|
| `run --save-debug debug/` | salva ogni fotogramma con disegnato quello che il bot vede |
| `run --max-taps 50` | si ferma dopo 50 tocchi |
| `screenshot -o foto.png` | salva uno screenshot del telefono |
| `analyze foto.png` | analizza uno screenshot e crea `foto_debug.png` |
| `simple --tap 0.5,0.75 --interval 0.2` | autoclicker classico su punti fissi (frazioni 0–1 o pixel) |
| `-s SERIALE` | sceglie il telefono se ne hai più di uno collegato |

## Se il tuo telefono ha uno schermo diverso

Le zone (disegno, slot, griglia, nastro) sono misurate su uno schermo **1080×2340** e salvate in
frazioni, quindi vanno bene per tutti i telefoni 19.5:9. Se il bot sbaglia:

```bash
python -m yarnloop_bot screenshot -o foto.png
python -m yarnloop_bot analyze foto.png      # guarda foto_debug.png
python -m yarnloop_bot config -o config.json # modifica i rettangoli/posizioni
python -m yarnloop_bot -c config.json run
```

Nell'immagine di debug: azzurro = zona disegno, viola = slot, giallo = griglia, verde = percorso del nastro,
rosso = zone ignorate, puntini = blocchi esposti, cerchio rosso = la mossa scelta.

Altre impostazioni in `config.json`:

- `tap_delay` – pausa dopo ogni tocco (alzala se il bot è troppo veloce);
- `max_on_belt` – quanti rocchetti al massimo sul nastro;
- `idle_tap` – es. `[0.5, 0.78]`: punto da toccare quando non si vede nessun livello
  (per premere "Continua" a fine livello). Disattivato di default, controlla prima dove sta il pulsante.

## Solo telefono (Termux, senza PC)

1. Installa **Termux** da F-Droid.
2. `pkg install python python-numpy python-pillow android-tools git`
3. Attiva **Debug wireless** nelle Opzioni sviluppatore, tocca *Associa dispositivo con codice*
   e (con lo schermo diviso) in Termux: `adb pair localhost:PORTA_ASSOCIAZIONE CODICE`,
   poi `adb connect localhost:PORTA_DEBUG`.
4. `git clone` questo repository, `cd autoclicker` e `python -m yarnloop_bot run`, poi torna nel gioco.

## Limiti

- I colori sono riconosciuti per tinta (rosso, rosa, pelle, arancio, marrone, giallo, verde, azzurro,
  blu, viola, …). Livelli con due tonalità molto simili possono confonderlo.
- I rocchetti **neri sul nastro** non si vedono bene (il nastro è scuro): il bot si ricorda però quelli
  che ha appena lanciato.
- Pubblicità e schermate di fine livello vanno gestite a mano (o con `idle_tap`).

## Test

```bash
python tests/test_livello31.py
```
