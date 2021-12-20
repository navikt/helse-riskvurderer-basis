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
 - Benytt så dataene fra RiskNeed (samt fra ev. ekstra oppslagsresultater) og benytt dette til å gjøre oppslaget.
   - Ankommede meldinger sendes som en `List<JsonObject>` inn til _oppslagsfunksjonen_ som igjen returnerer et `JsonElement` som er resultatet.
 - Send resultatet av oppslaget ut på `River` i en melding med `type=oppslagsresultat`, `infotype=<som-definert-i-OppslagsApp-constructor>`,
   `vedtaksperiodeId=<samme-som-i-innkommende-meldinger>` og `data=<oppslagsresultatet-i-json-format>`

En VurderingsApp fungerer typisk slik:
 - Sitt og lytt på sp-risk-modulens interne kafka-topic `helse-risk-river` (som for OppslagsApp)
 - Reager når det kommer en melding med `type=RiskNeed`, men vent typisk med å reagere til det _også_ har ankommet
   en melding med f.eks. `type=oppslagsresultat` og `infotype=oppslagstype1`, og typisk også _flere_ oppslagsresultater enn bare ett.
   - Akkurat på samme måte som for en `OppslagsApp`, men typisk kreves det ett eller flere oppslagsresultater i tillegg til et `RiskNeed`
   - Venter _maks-så-og-så-mange-sekunder_ på at "Interessene" ankommer, på samme måte som en `OppslagsApp`, og feiler typisk på samme måte dersom en forventet _Interesse_ uteblir.
 - Benytt så dataene fra RiskNeed og ev. oppslagsresultater til å gjøre _vurderingen_ (send ankommede meldinger som en `List<JsonObject>` inn til _vurderingsfunksjonen_)
   - VurderingsAppen benytter `VurderingBuilder` til å lage en `Vurdering` (se: [VurderingsApp.kt](src/main/kotlin/no/nav/helse/risk/VurderingsApp.kt)) som returneres av _vurderingsfunksjonen_.
 - Send resultatet av vurderingen ut på `River` i en melding med `type=vurdering`, `infotype=<kafkaClientId-som-definert-i-VurderingsApp-constructor>` 
   og ellers med felter som angitt i `Vurderingsmelding` (se [Meldinger.kt](src/main/kotlin/no/nav/helse/risk/Meldinger.kt)).

Som nevnt over kan både en OppslagsApp og en VurderingsApp feile (f.eks. fordi nødvendige "Interesser" ikke ankommer innenfor tidsvinduet). 
Dette vil føre til en såkalt `ufullstendig` vurdering. Dette håndteres rett og slett bare ved å prøve å kjøre vurderingen på nytt (`Retry`) og utføres
i et samspill mellom appene [Sigmund](https://github.com/navikt/helse-sigmund) og [Datalagrer](https://github.com/navikt/helse-risk-datalagrer)
(Se disse for nærmere beskrivelse av denne funksjonaliteten. Det eneste i `helse-riskvurderer-basis` som har med dette å gjøre er at 
meldingstypen `RiskNeed` er definert i denne modulen (i [Meldinger.kt](src/main/kotlin/no/nav/helse/risk/Meldinger.kt)), med feltene `isRetry` og `retryCount`,
selv om OppslagsApper og VurderingsApper normalt ikke har (og normalt ikke bør ha) noe som helst forhold til dette.)
