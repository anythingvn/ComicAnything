enum ComicSource { local, googleDrive }
enum ComicFormat { pdf, cbz, cbr, epub, mobi }
enum ReadingMode { ltr, rtl, webtoon, dualSpread }
enum ColorFilterMode { original, sepia, night, amoledBlack, highContrast }

class ComicItem {
  final String id;
  final String title;
  final String pathOrUrl;
  final ComicSource source;
  final ComicFormat format;
  final String? coverUrl;
  final int totalPages;
  int currentPage;
  double progressPercentage;
  DateTime lastReadTime;
  bool isFavorite;
  String? folderName;

  ComicItem({
    required this.id,
    required this.title,
    required this.pathOrUrl,
    required this.source,
    required this.format,
    this.coverUrl,
    this.totalPages = 0,
    this.currentPage = 1,
    this.progressPercentage = 0.0,
    required this.lastReadTime,
    this.isFavorite = false,
    this.folderName,
  });

  ComicItem copyWith({
    int? currentPage,
    int? totalPages,
    double? progressPercentage,
    DateTime? lastReadTime,
    bool? isFavorite,
  }) {
    return ComicItem(
      id: id,
      title: title,
      pathOrUrl: pathOrUrl,
      source: source,
      format: format,
      coverUrl: coverUrl,
      totalPages: totalPages ?? this.totalPages,
      currentPage: currentPage ?? this.currentPage,
      progressPercentage: progressPercentage ?? this.progressPercentage,
      lastReadTime: lastReadTime ?? this.lastReadTime,
      isFavorite: isFavorite ?? this.isFavorite,
      folderName: folderName,
    );
  }
}
