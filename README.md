# Autoclicker per Yarn Loop

Bot che gioca da solo a **Yarn Loop** su Android. Non tocca a caso: ad ogni turno fa uno
screenshot del telefono, capisce la situazione e tocca il rocchetto giusto.

Come ragiona (tattica "a raffica": il nastro deve essere sempre pieno, ad ogni screenshot
tocca tanti rocchetti quanti sono i posti liberi indicati dal contatore "N/5"):

1. **Disegno** – guarda i blocchi di lana sul bordo del disegno, cioè quelli che i rocchetti
   sul nastro possono raggiungere (da sopra, sotto, sinistra e destra), e li conta per colore.
2. **Nastro** – conta i rocchetti che stanno già girando, per non mandarne troppi dello stesso colore.
3. **Slot** – se un rocchetto negli slot di attesa ha un colore che serve, lo rimette in gioco per primo
   (così gli slot non si riempiono).
4. **Griglia** – altrimenti tocca il rocchetto in prima fila col colore più richiesto.
5. **Scavo** – se nessun colore utile è in prima fila, toglie subito (senza aspettare il nastro)
   i rocchetti che ne coprono uno utile, lasciando sempre uno slot libero.

Regole speciali che conosce:

- **Rocchetti collegati** da un'asta (anche negli slot): partono solo se sono entrambi in prima fila,
  quindi il bot li tocca solo in quel caso e, se serve, scava in tutte e due le colonne.
- **Distributori** (blocchi grigio-blu col numero): la loro colonna ha la precedenza; quello che c'è
  dietro non si può raggiungere finché il distributore non si svuota.
- **Lana bianca**: riconosciuta sia nel disegno sia nei rocchetti.
- **Rocchetti misteriosi "?"**: colore sconosciuto, il bot non scava per raggiungerli.
- Ogni posto libero sul nastro va al rocchetto che **raccoglie più lana** (numero e lana esposta);
  chi non può finire torna negli slot, quindi ne manda solo finché negli slot c'è posto.
- Se un rocchetto toccato **non parte**, il bot lo lascia stare per qualche secondo e prova altro.
- **Numeri**: legge il contatore dei posti liberi sul nastro, la lana che manca a ogni rocchetto
  (slot e prima fila) e quanti rocchetti restano nei distributori. Negli slot rimette in giro per primi
  quelli che possono finire (numero ≤ lana esposta). I modelli delle cifre si rigenerano con
  `python tools/make_digits.py`.

Non tocca mai la barra dei bonus in basso (quelli che costano monete).

## App Android (consigliata, senza PC)

1. Scarica e installa [`apk/YarnLoopBot.apk`](apk/YarnLoopBot.apk) (Android 11 o superiore).
   Se chiede il permesso, consenti l'installazione da questa fonte.
2. Apri **Yarn Loop Bot** → *Apri impostazioni Accessibilità* → attiva **Yarn Loop Bot**.
   Se l'interruttore è grigio ("impostazione con limitazioni"): *Info app* → ⋮ in alto a destra →
   **Consenti impostazioni con limitazioni**, poi riprova.
3. Apri Yarn Loop. In alto a sinistra compare un pulsante: **▶** avvia, **■** ferma,
   **✥** trascinalo per spostarlo, **tieni premuto ▶** per salvare uno screenshot in *Immagini/YarnLoopBot*.

Nell'app puoi cambiare la pausa tra i tocchi, il massimo di rocchetti sul nastro e attivare la
modalità "solo prova". Il codice è in [`android/`](android/) (Java, nessuna libreria esterna):
`cd android && gradle assembleRelease`.

## Versione Python (PC + ADB)

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
- `auto_buttons` – fuori dal livello tocca da solo **Riprova**, **Continua** (pannelli senza offerte),
  la **X rossa** dei popup e il pulsante **Livello N** della Home. Mai offerte a pagamento,
  "Continua a giocare" con le monete o pubblicità. La schermata deve essere uguale in due screenshot di fila.

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
- Le **pubblicità** vanno chiuse a mano. La schermata di **livello vinto** non l'ho ancora vista:
  se il bot non la riconosce, aspetta senza toccare.
- Sicurezza: i rocchetti si toccano solo quando si legge il contatore del nastro "N/5";
  sotto popup, offerte e pubblicità il contatore non si vede e il bot non tocca la griglia.

## Test

```bash
python tests/test_livello31.py
```
