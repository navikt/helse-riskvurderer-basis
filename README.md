# helse-riskvurderer-basis

Bibliotek som implementerer funksjonalitet som er felles for mange av appene i "sykepenge-risk-modulen".

Først og fremst er den basis for de mange såkalte oppslags- og vurderings-("mikro")tjenestene som alle
er instanser av h.h.v. 
[OppslagsApp](src/main/kotlin/no/nav/helse/risk/OppslagsApp.kt) / [EnTilEnOppslagsApp](src/main/kotlin/no/nav/helse/risk/EnTilEnOppslagsApp.kt)
eller [VurderingsApp](src/main/kotlin/no/nav/helse/risk/VurderingsApp.kt) (og som igjen er underklasser av [RiverApp](src/main/kotlin/no/nav/helse/risk/RiverApp.kt)).

Deler av funksjonaliteten benyttes også av andre risk-apper (bl.a. [Sigmund](https://github.com/navikt/helse-sigmund)),
slik som felles meldingsformater ([Meldinger](src/main/kotlin/no/nav/helse/risk/Meldinger.kt)) som bl.a. inkluderer det sentrale såkalte ```RiskNeed```,
samt - hva gjelder "Sigmund" - også *"kafka-time-windowing"*-funksjonaliteten i [WindowBufferEmitter](src/main/kotlin/no/nav/helse/buffer/WindowBufferEmitter.kt).


### Oppslags- og VurderingsApper

En OppslagsApp fungerer typisk slik:
 - Sitt og lytt på sp-risk-modulens interne kafka-topic `helse-risk-river` . . .
   - Topicen er ofte referert til som bare `River` 
     (i kontrast til det som omtales som `Rapid` og som i riskmodulens verden alltid refererer til Sykepengevedtaksløsningens hoved-kafka-topic)
   - Kafkaconfigen er felles / *"by convention"*, og finnes i [RiverEnvironment](src/main/kotlin/no/nav/helse/risk/RiverEnvironment.kt).
 - Reager når det kommer en melding med `type=RiskNeed`, *eventuelt* vent med å reagere til det f.eks. _også_ har ankommet 
   en melding med `type=oppslagsresultat` og `infotype=oppslagstype1` og hvor feltet `vedtaksperiodeId` (i `oppslagstype1`-meldingen) har samme verdi som `vedtaksperiodeId` i `RiskNeed`-meldingen, men vent maks så-og-så mange sekunder.
   - Det er denne *"vent-så-og-så-lenge på sånn-og-sånne meldinger med samme vedtaksperiodeId"* som er den 
     såkalte *"kafka-time-windowing"*-funksjonaliteten i [WindowBufferEmitter](src/main/kotlin/no/nav/helse/buffer/WindowBufferEmitter.kt) /
     [RiverApp](src/main/kotlin/no/nav/helse/risk/RiverApp.kt).
   - Hver av disse to meldingstypene ("RiskNeed" og "oppslagsresultat med infotype=oppslagstype1") er da en såkalt [Interesse](src/main/kotlin/no/nav/helse/risk/Interesse.kt) for akkurat denne OppslagsAppen.
   - Hvis det har gått _maks-så-og-så-mange-sekunder_ men ikke alle _interessene_ er til stede så vil typisk oppslags-funksjonen allikevel
     kjøre, men vil typisk gå på en Exception, og oppslaget vil feile (og oppslagsAppen vil ikke svare, som igjen typisk fører til
     at en `VurderingsApp` igjen feiler p.g.a. manglende data)
   - Hvis det kun trengs én melding - typisk da `RiskNeed`-meldingen - så trengs egentlig ikke window-funksjonaliteten og oppslagsappen kan implementeres som en [EnTilEnOppslagsApp](src/main/kotlin/no/nav/helse/risk/EnTilEnOppslagsApp.kt)
 - Benytt så dataene fra RiskNeed (samt fra ev. ekstra oppslagsresultater) til å gjøre det riktige oppslaget.
   - Ankommede meldinger sendes som en `List<JsonObject>` inn til _oppslagsfunksjonen_ som igjen returnerer et `JsonElement` som er resultatet.
     - Denne _oppslagsfunksjonen_ er da det en spesifikk implementasjon/instans av en OppslagsApp består av, og sendes inn som parameteren `oppslagstjeneste` 
       i [OppslagsApp](src/main/kotlin/no/nav/helse/risk/OppslagsApp.kt) sin constructor. 
 - Send resultatet av oppslaget ut på `River` i en melding med `type=oppslagsresultat`, `infotype=<som-definert-i-OppslagsApp-constructor>`,
   `vedtaksperiodeId=<samme-som-i-innkommende-meldinger>` og `data=<oppslagsresultatet-i-json-format>`

En VurderingsApp fungerer typisk slik:
 - Sitt og lytt på sp-risk-modulens interne kafka-topic `helse-risk-river` (som for OppslagsApp)
 - Reager når det kommer en melding med `type=RiskNeed`, men vent typisk med å reagere til det _også_ har ankommet
   en melding med f.eks. `type=oppslagsresultat` og `infotype=oppslagstype1`, og typisk også _flere_ oppslagsresultater enn bare ett.
   - Akkurat på samme måte som for en `OppslagsApp`, men typisk kreves det ett eller flere oppslagsresultater i tillegg til et `RiskNeed`
   - Venter _maks-så-og-så-mange-sekunder_ på at "Interessene" ankommer, på samme måte som en `OppslagsApp`, og feiler typisk på samme måte dersom en forventet _Interesse_ uteblir.
 - Benytt så dataene fra RiskNeed og ev. oppslagsresultater til å gjøre _vurderingen_ 
   (send ankommede meldinger som en `List<JsonObject>` inn til _vurderingsfunksjonen_ 
   (d.v.s. til implementasjonen av vurderingstjenesten som er angitt som parameteren `vurderer` i [VurderingsApp](src/main/kotlin/no/nav/helse/risk/VurderingsApp.kt) sin constructor))
   - VurderingsAppen benytter `VurderingBuilder` til å lage en `Vurdering` (se: [VurderingsApp.kt](src/main/kotlin/no/nav/helse/risk/VurderingsApp.kt)) som returneres av _vurderingsfunksjonen_.
 - Send resultatet av vurderingen ut på `River` i en melding med `type=vurdering`, `infotype=<kafkaClientId-som-definert-i-VurderingsApp-constructor>` 
   og ellers med felter som angitt i `Vurderingsmelding` (se [Meldinger.kt](src/main/kotlin/no/nav/helse/risk/Meldinger.kt)).

Som nevnt over kan både en OppslagsApp og en VurderingsApp feile (f.eks. fordi nødvendige "Interesser" ikke ankommer innenfor tidsvinduet). 
Dette vil føre til en såkalt `ufullstendig` vurdering. Dette håndteres rett og slett bare ved å prøve å kjøre vurderingen på nytt (`Retry`) og utføres
i et samspill mellom appene [Sigmund](https://github.com/navikt/helse-sigmund) og [Datalagrer](https://github.com/navikt/helse-risk-datalagrer)
(Se disse for nærmere beskrivelse av denne funksjonaliteten. Det eneste i `helse-riskvurderer-basis` som har med dette å gjøre er at 
meldingstypen `RiskNeed` er definert i denne modulen (i [Meldinger.kt](src/main/kotlin/no/nav/helse/risk/Meldinger.kt)), med feltene `isRetry` og `retryCount`,
selv om OppslagsApper og VurderingsApper normalt ikke har (og normalt ikke bør ha) noe som helst forhold til dette.)


### Diverse kjerne- og "convenience"-funksjonalitet:


#### "WindowBufferEmitter" + "BufferedRiver":

Som nevnt ovenfor samler Oppslags- og Vurderings-Apper opp relaterte meldinger (d.v.s. meldinger med samme `vedtaksperiodeId`) fra Kafka som ankommer innenfor et gitt tidsvindu (på "så-og-så-mange-sekunder").

Kjernefunksjonaliteten for å gjøre dette er implementert i [WindowBufferEmitter](src/main/kotlin/no/nav/helse/buffer/WindowBufferEmitter.kt).
Dette er grovt forklart rett og slett bare en in-memory buffer/map over ankommede meldinger+timestamp gruppert på en sesjonsId (egentlig på komboen sessionKey+kafkaKey).
 - I [BufferedRiver](src/main/kotlin/no/nav/helse/risk/BufferedRiver.kt) er denne `sessionKey` = `vedtaksperiodeId`
 - For at meldingen skal lagres i minne v.h.a. `BufferedRiver` så må meldingen i tillegg til å "ha ankommet" også svare til en såkalt [Interesse](src/main/kotlin/no/nav/helse/risk/Interesse.kt).
 - Hovedgrunnen til at også `kafkaKey` er en del av sesjonsId´en, er at kafkaKey ikke nødvendigvis må være det samme som sessionKey (i.e: som vedtaksperiodeId), _men_:
   Den _må_ være felles innad i en sesjon, fordi en sesjon (i en Oppslags- eller Vurderings-App) resulterer i _én ny_ melding ut på Kafka-topicen som skal ha samme kafkaKey som de innkommede meldingene.

En "sesjon" (i.e: en samling meldinger med samme sesjonsId) kan _"emittes"_ (d.v.s. sendes videre til oppslags- eller vurderings-funksjonen) av to forskjellige årsaker:
 - Tidsvinduet er ute: Dette håndteres av en "scheduler" som løper gjennom sesjonene ved gitte intervaller for å sjekke om noen av de er utløpt,
   og er per nå hardkodet til å kjøre hvert 5. sekund i `BufferedRiver` (altså i alle Oppslags- og Vurderings-apper) 
   - I [WindowBufferEmitter](src/main/kotlin/no/nav/helse/buffer/WindowBufferEmitter.kt) styres dette v.h.a. parametrene `scheduleExpiryCheck` + `schedulerIntervalInSeconds`
 - Sesjonen er "komplett": Det vil si: Alle angitte ["Interesser"](src/main/kotlin/no/nav/helse/risk/Interesse.kt) er tilstede. 
   Hvorvidt dette skal skje angis v.h.a. `emitEarlyWhenAllInterestsPresent`=`true/false` i Oppslags- og Vurderings-appene 
   (som da igjen setter parameteren `sessionEarlyExpireCondition` i [WindowBufferEmitter](src/main/kotlin/no/nav/helse/buffer/WindowBufferEmitter.kt)).
 
Noen forskjeller fra Time-Windowing i Kafka Streams:
 - Kafka Streams er "failsafe" (v.h.a. intermediate topics og/eller database-backing(?)), det er _ikke_ `WindowBufferEmitter`. D.v.s.: Meldinger som er buffret i minne når f.eks. appen restarter vil gå tapt.
   Dette løses rett og slett bare ved å kjøre en `Retry` dersom en vurdering blir `ufullstendig` (som nevnt ovenfor).
 - `WindowBufferEmitter` er enklere og - i og med skreddersydd - gir mer kontroll over når/hvordan en sesjon avsluttes
   - i.e: `sessionEarlyExpireCondition` som nevnt ovenfor.
   - Vindusutløp håndheves strengt av en scheduler i `WindowBufferEmitter`, mens det i Kafka Streams sjekkes timestamp kun når _neste_ melding på samme "sesjon" ankommer(?),
     og sesjoner kan bli kuttet både lenge før og lenge etter angitt ønsket vinduslengde, noe som ikke egner seg for "RiverApp´enes" formål.


#### Kryptering av oppslagsdata:

For å begrense avtrykkene av f.eks. eventuelle persondata som flyter mellom appene er det lagt på en mulighet for veldig enkel kryptering av `data`-feltet i `oppslagsresultat`-meldingene med statiske nøkler.
Dette kan hjelpe til med å unngå f.eks. at oppslagsdata unødvendig flyter i klartekst gjennom en annen mikrotjeneste som _ikke trenger_ dataene, 
men som lytter på samme kafka-topic, eller at oppslagsdataene er tilgjengelig i klartekst for Kafka-brokeren.

Nøkler som skal benyttes til å kryptere/dekryptere innholdet i `data`-feltet angis v.h.a. constructor-parametrene `encryptionJWK` (kun `OppslagsApp`) 
og `decryptionJWKS` (både `OppslagsApp` og `VurderingsApp`).

For å generere en statisk AES-256 nøkkel for en oppslagsapp kan man benytte [JWKUtil](src/test/kotlin/no/nav/helse/crypto/JWKUtil.kt):
 - Bytt ut `oppslagsdata` i `val base = "oppslagsdata"` med en identifikator for dataene, f.eks. "my-special-data"
 - Kjør `JWKUtil.main()` for å generere kubectl-kommandoer for å legge inn nøkkelen som en k8s-secret samt koden som må inn i 
   NAIS-yaml og i Kotlin i "sender" (en OppslagsApp) og "receiver" (en VurderingsApp eller en annen OppslagsApp).


#### Caching av oppslagsdata:

Siden en vurderingsforespørsel mot riskmodulen er avhengig av at hver del skjer innenfor visse tidsvinduer 
(fordi - som beskrevet over - hver mikrotjeneste venter "så-og-så-mange-sekunder" på at dateene den trenger blir tilgjengelig på Kafka),
og siden oppslagsdata heller ikke blir mellomlagret i noen database, så blir det slik at om de samme oppslagsdataene trengs på nytt igjen like etterpå
(f.eks fordi vurderingsforespørselen feilet (teknisk) og vi må kjøre en `Retry`, eller fordi "grunnvurderingen" (iterasjon 1) taler for at det skal kjøres
en "utvidet vurdering" (iterasjon 2)), så må i prinsippet akkurat samme dataoppslag (potensielt mot ekstern tjeneste) også gjøres på nytt.

For da å slippe å potensielt måtte gjøre akkurat samme oppslag flere ganger rett etterhverandre gjør derfor de fleste av oppslagstjenestene en kortvarig caching
av oppslagsresultatene i minnet (på pod´en/app-instansen).

Til dette benyttes [InMemoryLookupCache](src/main/kotlin/no/nav/helse/risk/cache/InMemoryLookupCache.kt) for å "wrappe" en oppslagsfunksjon hvorpå
resultatet (med mindre det er `null`) caches med oppslagsfunksjonsparametrene som nøkkel/referanse (For eksempel, se [InMemoryLookupCacheTest](src/test/kotlin/no/nav/helse/risk/cache/InMemoryLookupCacheTest.kt)).

- Returdataene fra oppslagsfunksjonen må være serialiserbare, og `InMemoryLookupCache` sin _constructor_ må få angitt en `serializer: KSerializer<RET>` hvor `RET` er oppslagsdataenes `type`. 
  - At dataene serialiseres _kan_ man tenke seg at kunne vært benyttet til å gjøre cachingen i en form for database, selv om det per nå ikke har vært noe behov for dette. 
  - Det faktum _at_ dataene serialiseres utnyttes dog per nå til å obfuskere cache-verdiene i minnet ved at de AES-krypteres med en nøkkel som er basert delvis
    på tilfeldige data generert ved oppstart av appen, og delvis på input-parametrene til oppslagsfunksjonen (Tanken er at dette vil kunne bidra til å redusere
    mengden data som vil være lett tilgjengelig samtidig ved f.eks. en minnedump).


#### Logging:

- Sanity.getSecureLogger():

Logging av parametre/data som kan være personidentifiserende er vanlig å logge til den tilgangsbegrensede "tjenesteloggindeksen". 
Dette krever at man kaller `getLogger()` med et loggernavn som er konfigurert på riktig måte i `logback.xml`.

For å enklere å _huske_ på dette for hver mikrotjeneste som opprettes så er konvensjonen i "risk-appene" at man henter "sikkerLogger"-instansen ved å kalle
`Sanity.getSecureLogger()` (definert i  [Sanity.kt](src/main/kotlin/no/nav/helse/risk/Sanity.kt)), 
som også gjør et forsøk på å verifisere at sikkerLogg-instansen er satt opp riktig, og kaster en Exception hvis ikke.

Siden denne verifiseringen gjerne feiler for oppsettet som gjelder for Unit-tester så må Unit-tester som kjører kode som igjen kaller `Sanity.getSecureLogger()`
legge inn denne kodesnutten i test-klassene for å skru av denne verifiseringen: `init { Sanity.setSkipSanityChecksForProduction() }`

 - IdMasker():

Hvis det er behov for å logge json-strukturer/oppslagsdata, men hvor kanskje ikke spesifikt person/bedrift-identifiserende informasjon er nødvendig for feilsøkingen,
så kan man filtrere vekk typisk direkte-identifiserende felter ved å kjøre json-strukturen gjennom `IdMasker().mask()`-funksjonen før de logges, hvor `IdMasker` er en
prekonfigurert versjon av [Masker](src/main/kotlin/no/nav/helse/privacy/Masker.kt). Verdien i typiske ident-felter vil da byttes ut med en verdi som er en
hash over den egentlige verdien og noen tilfeldige data som ble generert ved oppstart av appen, slik at maskeringen blir konsistent innenfor app-instansens levetid
(det vil si: samme ID-verdi vil da nødvendigvis bli byttet ut med samme erstatnings-verdi i hele datasettet som eventuelt logges).

Dersom `IdMasker` _ikke_ maskerer felter som _skulle_ vært maskert, eller hvis den _maskerer_ felter som _ikke_ skulle vært maskert, 
så kan dette justeres v.h.a. parametrene `fieldNames` (som skal maskeres) og `primitiveFieldNamesExcludedFromMasking` (som ikke skal maskeres).


