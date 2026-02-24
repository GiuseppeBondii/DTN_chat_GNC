### ARCHITETTURA DEL SISTEMA (Livelli)

**A. Livello UI (`MainActivity.kt`, `MainViewModel.kt`)**
Gestisce l'interfaccia utente con Jetpack Compose. Visualizza i log, lo stato dei nodi e i controlli Start/Stop. L'interfaccia è puramente reattiva: mostra ciò che il `MeshManager` (tramite il ViewModel) le comunica.
*Novità:*

* **Divisione Utenti:** Categorizza dinamicamente i dispositivi in "Online (Chat Recenti)", "Online (Nuovi)" e "Offline (Storico)".
* **Pannello di Debug:** Una sezione fissa in basso che mostra in tempo reale i log di sistema, la topologia dell'albero di rete e lo stato della coda DTN.
* **Avvio Automatico:** L'advertising e la scansione partono automaticamente all'apertura dell'app.

**B. Livello Archiviazione (`ChatStorage.kt`, `PrefsManager.kt`)**

* **`ChatStorage`:** Salva in locale (tramite file JSON su thread asincroni) la cronologia dei messaggi scambiati, mappandoli per ID utente.
* **`PrefsManager`:** Mantiene in memoria persistente (SharedPreferences) l'ID univoco del proprio nodo, il nome personalizzato e una rubrica dei contatti conosciuti associando ID a Nomi.

**C. Livello Hardware (`MeshManager.kt`)**
Agisce da wrapper per le API Google Nearby Connections. Si occupa di:

* Advertising (trasmissione beacon).


* Discovery (scansione dispositivi).


* Gestione connessioni fisiche (Payload byte array).
Strategia utilizzata: `P2P_CLUSTER` (permette topologie molti-a-molti).



**D. Livello Protocollo (`MeshProtocolManager.kt`)**
Il "cervello" del sistema. Implementa l'algoritmo DFS, gestisce l'elezione del leader, mantiene la tabella dei vicini e costruisce l'albero topologico. Ora gestisce anche il routing dei messaggi e il protocollo DTN.

---

### FASI DI FUNZIONAMENTO

**FASE 1: Connessione**

1. Il dispositivo inizia contemporaneamente l'Advertising (per farsi trovare) e la Discovery (per trovare altri).


2. Appena due dispositivi si rilevano, Google Nearby stabilisce una connessione crittografata.


3. Callback: `onConnectionInitiated` accetta automaticamente la connessione.


4. Callback: `onConnectionResult` conferma il successo. A questo punto esiste un "canale tubolare" di byte, ma i dispositivi non sanno ancora "chi" c'è dall'altra parte.



**FASE 2: Handshake e Riconoscimento**

1. Appena connesso, il `MeshManager` invoca `protocol.sendHelloTo(endpointId)`.


2. Viene inviato un pacchetto JSON di tipo `HELLO` contenente l'ID univoco del nodo  e, ora, anche il "Nome Visualizzato".


3. Chi riceve il pacchetto associa l'Endpoint ID di Google all'ID Logico e al Nome del nodo. Lo aggiunge alla lista `physicalNeighbors` e logga la connessione.


4. Reset: L'arrivo di un nuovo vicino fa ripartire il timer per l'elezione (perché la topologia è cambiata).



**FASE 3: Elezione del Leader**

1. Ogni nodo ha un timer silenzioso (Coroutine). Se per 20 secondi non c'è traffico di rete (nessun token ricevuto), il nodo presume di essere solo o il primo pronto.


2. Il nodo si "Auto-elegge": crea un oggetto `TopologyNode` radice con il proprio ID , genera un `DFS_TOKEN` con un Timestamp attuale e lo invia ai suoi vicini fisici.


3. Se due nodi si eleggono insieme, quando i loro pacchetti si incrociano, vince quello con il Timestamp più vecchio (chi ha iniziato prima) o, in caso di pareggio, l'ID maggiore. Il perdente cessa di essere Leader.



**FASE 4: Algoritmo DFS (Costruzione Topologia)**
L'algoritmo è una "Depth First Search" distribuita tramite passaggio di Token. Il Token è un pacchetto che contiene l'intero oggetto `rootTopology` (la mappa parziale).

1. **Ricezione:** Un nodo riceve il token. Cerca se stesso nell'albero. Se non c'è, è un errore. Se c'è, identifica il mittente come suo "Padre" nell'albero.


2. 
**Espansione:** Il nodo confronta i suoi `physicalNeighbors` con la lista di tutti gli ID già presenti nel Token ("visited_ids"). SE trova un vicino NON visitato, lo aggiunge come "Figlio" nell'albero , invia il Token aggiornato a quel vicino e attende che ritorni. Contemporaneamente aggiorna la sua *Routing Table* verso i discendenti.


3. 
**Backtracking:** SE tutti i vicini sono già stati visitati, il nodo si segna come completato (`isReady = true`) e invia il Token aggiornato indietro al suo Padre.


4. 
**Completamento:** Quando il Token risale fino alla Radice (il Leader) e la Radice non ha più vicini inesplorati, la mappa è completa e viene generato l'evento `BOSS ELECTED`.



**FASE 5: Routing, Messaggistica e DTN (Spray and Wait)**
Il protocollo gestisce l'invio dei messaggi `MESSAGE` analizzando l'albero topologico aggiornato:

1. **Destinatario Online (Routing DFS):** Se il destinatario è mappato nell'albero corrente, il messaggio viene inviato al "next hop" (il Padre o un Figlio) usando la Routing Table, fino a destinazione.
2. **Destinatario Offline (Spray and Wait DTN):**
* *Fase Spray:* Il mittente calcola dinamicamente le copie da spruzzare tramite la formula $L = \sqrt{N}$ (dove $N$ è il numero di nodi correnti). Tiene 1 copia nella sua `dtnQueue` e invia le altre ai suoi vicini fisici casuali con il flag `isDtn = true`.
* *Fase Wait:* I nodi ponte ricevono il messaggio e lo mettono nella loro coda `dtnQueue`. La memoria massima è allocata a 5 messaggi.
* *Hot Potato (Delega FIFO):* Se la coda DTN di un nodo si riempie, il nodo applica una politica FIFO: rimuove il messaggio più vecchio e lo "delega" a un vicino fisico scelto a caso.
* *Soppressione senza ACK:* Se un nodo ha in memoria un messaggio DTN e "vede" passare una copia di quello stesso messaggio indirizzata alla destinazione finale (es. un altro nodo lo sta consegnando), cancella silenziosamente la sua copia locale per non congestionare la rete.
* *Consegna:* Alla fine di ogni ciclo DFS, ogni nodo controlla la propria `dtnQueue`. Se la destinazione di un bundle è tornata online (presente nell'albero appena ricostruito), il pacchetto viene re-inserito nel routing normale e consegnato, rimuovendolo dalla coda.



**FASE 6: Gestione Errori e Disconnessioni**

1. Se Google Nearby segnala `onDisconnected`, il protocollo rimuove il nodo dai vicini  e ricalcola dinamicamente le UI list.


2. Controllo Orfani: Se il nodo disconnesso era il "Padre" corrente nel processo DFS, il nodo rimane "Orfano". Viene dichiarato uno stato di WARNING/DANGER e si tenta di avviare immediatamente una nuova elezione.


3. Se viene rilevata una rottura critica, un pacchetto "ALARM" viene inviato in broadcast per resettare lo stato di tutti i nodi.




---

### STRUTTURE DATI CHIAVE

* **`TopologyNode`:**
La struttura ricorsiva che forma la mappa.
`{ id: "A", children: [ { id: "B", children: [...] } ] }` 


* **`MessageData`:**
L'oggetto che modella il payload dei messaggi in chat. Contiene `id` univoco, mittente, destinatario, contenuto, `timestamp` e il flag booleano `isDtn` fondamentale per l'algoritmo Spray and Wait.
* **`MeshPacket`:**
Il contenitore JSON scambiato sulla rete. Ora espanso:
`{ type: HELLO | DFS_TOKEN | ALARM | MESSAGE, senderId: "Chi me lo manda ora", senderName: "Nome in chiaro del mittente", sourceId: "Chi ha iniziato il giro", timestamp: Long, rootTopology: { ...albero... }, messageData: { ...dati chat o DTN... } [cite_start]}`