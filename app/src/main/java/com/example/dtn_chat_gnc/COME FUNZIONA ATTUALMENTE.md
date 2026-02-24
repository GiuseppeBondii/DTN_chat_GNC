Documentazione Tecnica: D2D Mesh Chat Protocol con supporto DTN

---

### 1. ARCHITETTURA DEL SISTEMA (Separazione dei Livelli)

L'architettura è strutturata in quattro layer principali, garantendo una rigida separazione tra logica di presentazione, persistenza, interfacciamento hardware e protocollo di rete.

**A. Livello Interfaccia Utente (UI Layer)**
Sviluppato in Jetpack Compose, adotta un pattern architetturale reattivo basato su `StateFlow` e `ViewModel`. Il livello UI si limita a sottoscrivere gli stati emessi dai layer inferiori senza implementare logica di business.

* **Gestione dello Stato:** Mantiene liste reattive che classificano i nodi in `Online (Chat Recenti)`, `Online (Nuovi)` e `Offline (Storico)`.
* **Debug Telemetry:** Rende visibile in tempo reale l'output della console di sistema, lo stato dell'albero topologico (serializzato in formato testuale indentato) e l'allocazione buffer della coda DTN.
* **Ciclo di Vita:** Avvia i servizi di rete (`startMesh()`) contestualmente all'inizializzazione del layer grafico.

**B. Livello di Persistenza (Storage Layer)**
Gestisce l'I/O su file system per garantire la persistenza dello stato tra le sessioni dell'applicazione, operando interamente su thread asincroni tramite Kotlin Coroutines (`Dispatchers.IO`).

* **`ChatStorage`:** Serializza/deserializza la mappa della cronologia dei messaggi (`chat_history.json`) e lo stato della coda dei messaggi in attesa di recapito (`dtn_queue.json`) utilizzando la libreria Gson.
* **`PrefsManager`:** Interfaccia con le `SharedPreferences` per l'archiviazione di parametri scalari e configurazioni statiche (ID di rete univoco a 8 byte, Display Name, e associazione ID-Nome dei peer scoperti).

**C. Livello di Collegamento e Trasporto (Hardware Wrapper Layer)**
Il modulo `MeshManager` astrae l'implementazione fisica delegata a Google Nearby Connections.

* **Discovery e Advertising:** Gestisce la trasmissione del beacon (`NODE_myId`) e la scansione simultanea su canali Bluetooth (Classic/BLE) e Wi-Fi Direct.
* **Topologia Fisica:** Forza l'utilizzo della strategia `Strategy.P2P_CLUSTER`, che instrada le connessioni creando topologie a stella o mesh parziali (molti-a-molti).
* **Trasferimento Payload:** Implementa le callback `ConnectionLifecycleCallback` e `PayloadCallback` per gestire l'apertura dei socket crittografati e la conversione dei flussi di byte in stringhe UTF-8 compatibili con JSON.

**D. Livello di Rete e Routing (Protocol Layer)**
Il modulo `MeshProtocolManager` agisce da motore decisionale. È agnostico rispetto al mezzo fisico e all'interfaccia utente.

* **Mappatura:** Mantiene tre dizionari critici: `endpointMap` (mapping tra ID Logico e ID Fisico del socket), `reverseEndpointMap`, e `peerNames`.
* **Stato dell'Albero:** Gestisce dinamicamente la topologia, i timer di elezione del Leader e popola la tabella di instradamento `downstreamRoutingTable`.

---

### 2. MACCHINA A STATI E FLUSSO OPERATIVO

Il ciclo di vita della rete si articola in fasi sequenziali, innescate da eventi hardware o da timeout logici.

**FASE 1: Handshake e Risoluzione degli Endpoint**

1. Alla notifica di `onConnectionResult` con esito positivo, il canale fisico è stabilito. Il `MeshProtocolManager` istruisce l'invio immediato di un pacchetto di tipo `HELLO`.
2. Il pacchetto `HELLO` contiene il Node ID logico e il Node Name.
3. Il ricevitore esegue il parsing del pacchetto, istanzia l'associazione tra l'ID fisico generato dal sistema operativo (es. "Xy9Z") e l'ID logico del nodo, e inserisce quest'ultimo nel set `physicalNeighbors`.
4. L'inserimento di un nuovo vicino invalida la topologia corrente e forza l'avvio immediato di un nuovo ciclo di ricalcolo tramite la funzione `startNewDfsRound(true)`.

**FASE 2: Elezione del Leader Coordinatore**
Per prevenire collisioni nella mappatura del grafo, la rete elegge temporaneamente un Root Node.

1. Il sistema utilizza un timer basato su Coroutines con un idle timeout di 20 secondi.
2. In assenza di traffico topologico in ingresso, il nodo si auto-elegge: crea una root instance di `TopologyNode`, genera un pacchetto `DFS_TOKEN` associandovi un timestamp locale (Epoch time) e lo inoltra ai vicini.
3. **Risoluzione delle collisioni:** Nel caso in cui due nodi propaghino simultaneamente un `DFS_TOKEN`, ogni nodo applica un algoritmo di confronto deterministico. Il pacchetto con il timestamp inferiore (generato per primo) ha la priorità. A parità di timestamp, prevale il nodo con l'identificativo alfanumerico maggiore. Il nodo soccombente interrompe la propria propagazione e adotta lo stato del vincitore.

**FASE 3: Depth-First Search (Costruzione dell'Albero di Routing)**
L'algoritmo distribuisce la computazione del grafo esplorando i nodi in profondità.

1. **Validazione e Parentesi:** Alla ricezione di un `DFS_TOKEN`, il nodo analizza l'oggetto `rootTopology`. Se si individua come destinatario, registra il mittente del pacchetto fisico come proprio `currentDfsParentNodeId`.
2. **Propagazione:** Il nodo computa la differenza tra il set `physicalNeighbors` e l'array degli ID già presenti nell'albero (`visited_ids`). Il primo vicino non visitato viene aggiunto come nodo figlio (`children.add`) nell'albero JSON e il token aggiornato viene trasmesso esclusivamente a quel nodo.
3. **Aggiornamento Routing Table:** Se il pacchetto in ricezione proviene da un nodo registrato come "Figlio", il nodo aggiunge tutti i discendenti di quel ramo alla propria `downstreamRoutingTable`, puntando all'ID del figlio.
4. **Backtracking:** Esauriti i vicini non visitati, il nodo imposta la flag `isReady = true` e restituisce il `DFS_TOKEN` al proprio nodo Padre. Quando il Token ritorna al Leader originario, la topologia globale è consolidata e pronta all'uso.

**FASE 4: Algoritmo di Routing Ibrido (DFS / DTN)**
La funzione `routeMessage(msg)` determina il destino di ogni pacchetto applicativo (tipo `MESSAGE`).

* **Scenario A: Destinatario nella Topologia (Routing Diretto)**
  Se il destinatario è presente nella `lastKnownTree`:
1. Si esegue un lookup nella `endpointMap` (connessione di 1 hop).
2. In caso di fallimento, si esegue un lookup nella `downstreamRoutingTable` (discendenti, routing N hops verso il basso).
3. In caso di fallimento, il pacchetto viene inviato al `currentDfsParentNodeId` (routing verso l'alto).
   Il pacchetto viene quindi inoltrato senza flag DTN.


* **Scenario B: Destinatario non raggiungibile (Spray and Wait DTN)**
  Se il destinatario è assente dall'albero o il Next Hop calcolato risulta invalido, interviene il protocollo Delay-Tolerant.
1. **Source Spray:** Se il nodo è il mittente originale, calcola dinamicamente il numero di copie come $L = \lfloor\sqrt{N}\rfloor$, dove $N$ è il cardinale dei nodi nell'albero. Il nodo inserisce 1 copia nel buffer locale (`dtnQueue`) e inoltra le restanti $L-1$ copie a sottoinsiemi randomici di `physicalNeighbors`, impostando il pacchetto come `isDtn = true`.
2. **Wait & Queue Management:** I nodi riceventi (relay) immagazzinano il payload. La dimensione del buffer è contingentata (`MAX_DTN_QUEUE_SIZE = 5`). In caso di overflow, la logica applica un'espulsione FIFO: l'elemento all'indice 0 viene rimosso dal buffer locale e delegato (inviato) forzatamente a un vicino casuale per garantire la sopravvivenza del pacchetto nella rete.
3. **Passive Suppression (Anti-Congestion):** Non vi è implementazione di ACK dedicati. Durante la routine `routeMessage`, se un nodo processa un pacchetto standard in transito e rileva un matching di UUID all'interno della propria `dtnQueue`, sopprime in modo silente la propria copia in ostaggio.
4. **Delivery Check:** Al completamento di ogni ricalcolo topologico, la funzione `checkDtnDelivery` itera sulla `dtnQueue`. Se una destinazione offline transita in stato online (presente nella nuova mappa), il pacchetto pertinente viene estratto dal buffer, de-flaggato dalla modalità DTN e re-immesso nel flusso di routing diretto.



**FASE 5: Gestione della Resilienza (Fault Tolerance)**

1. **Link Failure:** Invocata l'interfaccia `onDisconnected`, il nodo esegue il purge del peer dalle tabelle fisiche. Questa azione forza un trigger sincrono per una nuova elezione e ricalcolo DFS.
2. **Orphan Handling:** Qualora la caduta del link interessi il nodo Padre (`currentDfsParentNodeId`), il ramo disconnesso si dichiara isolato, passando in priorità per indire un'elezione immediata allo scopo di ricollegarsi alla root o eleggersi come nuova root di partizione.

---

### 3. STRUTTURE DATI E SERIALIZZAZIONE (JSON Payloads)

La comunicazione via payload byte richiede la serializzazione rigorosa degli stati.

**1. Oggetto `TopologyNode**`
Struttura dati ricorsiva per la rappresentazione dell'albero.

```json
{
  "id": "A1B2",
  "parentId": "X9Y8",
  "isReady": true,
  "children": [
    {
      "id": "Z7W6",
      "parentId": "A1B2",
      "isReady": true,
      "children": []
    }
  ]
}

```

**2. Oggetto `MessageData**`
Payload contenente l'informazione applicativa e i metadati vitali per il calcolo DTN. L'UUID previene la duplicazione in fase di Spray.

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "senderId": "A1B2",
  "destinationId": "Z7W6",
  "content": "Payload testuale crittografato o in chiaro",
  "timestamp": 1709568492000,
  "isDtn": true 
}

```

**3. Involucro di Trasporto `MeshPacket**`
Il frame principale instradato sulle API Nearby Connections.

```json
{
  "type": "MESSAGE", 
  "sourceId": "C3D4", 
  "senderId": "A1B2", 
  "senderName": "Node_Alpha_01",
  "timestamp": 1709568492100,
  "safetyLevel": "SAFE",
  "rootTopology": null, 
  "messageData": { 
     "id": "550e8400-e29b-41d4-a716-446655440000",
     "senderId": "...",
     "destinationId": "...",
     "content": "...",
     "isDtn": false
  }
}

```

I tipi ammessi (`PacketType`) sono:

* `HELLO`: Risoluzione MAC/Endpoint in Logical ID in FASE 1.
* `DFS_TOKEN` / `CPL_TOKEN`: Frame di passaggio dell'oggetto `rootTopology` (computazione grafo).
* `ALARM`: Pacchetto di flooding in broadcast per invalidare la topologia a livello globale in caso di segmentazione critica della rete.
* `MESSAGE`: Frame dati standard per il payload applicativo o DTN.