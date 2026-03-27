# X chi sta cercando i ringraziamenti della mia tesi
Ringrazio tutte le persone che ritengono un ringraziamento nei loro confronti sia dovuto o gradito per qualsiasi cosa abbiamo fatto per ritenere giusto che vengano ringraziati

# DTN_chat_GNC: Routing Ibrido MANET-DTN su Android

> Proof-of-Concept di un'applicazione di messaggistica decentralizzata per scenari off-grid, basata su un protocollo di routing ibrido a livello applicativo (overlay network).

Questo progetto è stato sviluppato come lavoro di tesi triennale in Informatica presso l'**Università di Bologna (Alma Mater Studiorum)**. Dimostra la fattibilità di trasformare comuni smartphone commerciali in una rete mesh resiliente, senza l'ausilio di privilegi di root o moduli hardware radio dedicati (approccio "zero-hardware").

##  Descrizione

Le reti centralizzate falliscono in scenari di emergenza, aree montane o grandi eventi. Questo sistema permette ai dispositivi di auto-organizzarsi e comunicare sfruttando nativamente Wi-Fi e Bluetooth tramite le API di **Google Nearby Connections**.

Il cuore del progetto è un **motore di routing ibrido** che si adatta alla topologia della rete:
1. **Fase MANET (Routing Deterministico):** All'interno di gruppi stabili, i nodi eleggono un Leader e mappano la rete tramite un algoritmo **Depth-First Search (DFS)**, creando una topologia ad albero priva di loop (loop-free) che azzera i broadcast storms.
2. **Fase DTN (Routing Opportunistico):** In caso di disconnessioni o partizioni di rete, il sistema adotta il paradigma *store-carry-and-forward*. Utilizza una variante adattiva del protocollo **Spray and Wait**, in cui il limite di repliche da iniettare è calcolato dinamicamente in base alla cardinalità $N$ dell'ultimo cluster noto: $L = \lfloor\sqrt{N}\rfloor$.

##  Caratteristiche Principali

* **Zero-Hardware:** Funziona su dispositivi Android standard (COTS) aggirando i limiti dello stack MAC/IP.
* **Algoritmo Adattivo:** Transizione trasparente e automatica tra instradamento multi-hop diretto e inoltro opportunistico (DTN).
* **Gestione Avanzata del Buffer:** * Espulsione FIFO con **delegetion**: i pacchetti vecchi non vengono scartati (drop-tail), ma delegati a un nodo vicino.
    * **Implicit ACK:** Soppressione passiva delle repliche ridondanti per risparmiare larghezza di banda.
* **UI Reattiva:** Interfaccia utente costruita in Jetpack Compose che si aggiorna in tempo reale con le mutazioni della topologia di rete.

##  Tecnologie Utilizzate

L'architettura segue il principio della *Separation of Concerns* (Livelli UI, Storage, Hardware Wrapper, Protocol Core).

* **Linguaggio:** Kotlin
* **Concorrenza:** Kotlin Coroutines & Flow (gestione asincrona I/O e rete)
* **Interfaccia Grafica:** Jetpack Compose (MVVM Architecture)
* **Livello di Rete Fisica:** Google Nearby Connections API (Strategia `P2P_CLUSTER`)
* **Serializzazione Dati:** Gson

##  Requisiti e Installazione

1. Clona la repository:
   ```bash
   git clone [https://github.com/GiuseppeBondii/DTN_chat_GNC.git](https://github.com/GiuseppeBondii/DTN_chat_GNC.git)
2. Apri il progetto con Android Studio.
3. Sincronizza Gradle per scaricare le dipendenze.
4. Compila ed esegui l'app su almeno due o più dispositivi fisici Android (gli emulatori non supportano correttamente l'hardware Bluetooth/Wi-Fi Direct necessario per Nearby Connections).
5. Assicurati di concedere i permessi di localizzazione e dispositivi vicini all'avvio dell'app.

Se sei interessato ai dettagli algoritmici, teorici e alla valutazione del protocollo, puoi consultare il documento completo della tesi (PDF) disponibile all'interno di questa repository.

