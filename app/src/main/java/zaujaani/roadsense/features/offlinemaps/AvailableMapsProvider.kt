package zaujaani.roadsense.features.offlinemaps

object AvailableMapsProvider {
    fun getAvailableMaps(): List<AvailableMap> {
        return listOf(
            AvailableMap(
                id = "bandung",
                name = "Bandung",
                description = "Kota Bandung dan sekitarnya",
                sizeMb = 350,
                url = "https://download.bbbike.org/osm/bbbike/Bandung/Bandung.mbtiles",
                provider = "BBBike",
                isDownloaded = false
            ),
            AvailableMap(
                id = "surabaya",
                name = "Surabaya",
                description = "Kota Surabaya dan sekitarnya",
                sizeMb = 420,
                url = "https://download.bbbike.org/osm/bbbike/Surabaya/Surabaya.mbtiles",
                provider = "BBBike",
                isDownloaded = false
            ),
            AvailableMap(
                id = "yogyakarta",
                name = "Yogyakarta",
                description = "Kota Yogyakarta dan sekitarnya",
                sizeMb = 280,
                url = "https://download.bbbike.org/osm/bbbike/Yogyakarta/Yogyakarta.mbtiles",
                provider = "BBBike",
                isDownloaded = false
            ),
            AvailableMap(
                id = "malang",
                name = "Malang",
                description = "Kota Malang dan sekitarnya",
                sizeMb = 300,
                url = "https://download.bbbike.org/osm/bbbike/Malang/Malang.mbtiles",
                provider = "BBBike",
                isDownloaded = false
            ),
            AvailableMap(
                id = "denpasar",
                name = "Denpasar",
                description = "Kota Denpasar, Bali",
                sizeMb = 200,
                url = "https://download.bbbike.org/osm/bbbike/Denpasar/Denpasar.mbtiles",
                provider = "BBBike",
                isDownloaded = false
            )
        )
    }
}