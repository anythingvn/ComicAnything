import 'package:flutter/material.dart';
import '../models/comic_item.dart';
import '../services/google_drive_service.dart';
import 'reader_view.dart';

class HomeView extends StatefulWidget {
  const HomeView({Key? key}) : super(key: key);

  @override
  State<HomeView> createState() => _HomeViewState();
}

class _HomeViewState extends State<HomeView> with SingleTickerProviderStateMixin {
  late TabController _tabController;
  final TextEditingController _driveUrlController = TextEditingController();
  final GoogleDriveService _driveService = GoogleDriveService();

  List<ComicItem> _libraryItems = [
    ComicItem(
      id: 'local_1',
      title: 'Batman: Year One (PDF)',
      pathOrUrl: '/storage/emulated/0/Download/Batman_Year_One.pdf',
      source: ComicSource.local,
      format: ComicFormat.pdf,
      currentPage: 14,
      totalPages: 48,
      progressPercentage: 0.29,
      lastReadTime: DateTime.now().subtract(const Duration(hours: 2)),
      isFavorite: true,
    ),
    ComicItem(
      id: 'local_2',
      title: 'Solo Leveling Vol 1 (CBZ)',
      pathOrUrl: '/storage/emulated/0/Comics/Solo_Leveling_v1.cbz',
      source: ComicSource.local,
      format: ComicFormat.cbz,
      currentPage: 3,
      totalPages: 120,
      progressPercentage: 0.02,
      lastReadTime: DateTime.now().subtract(const Duration(days: 1)),
    ),
  ];

  List<ComicItem> _driveItems = [];
  bool _isLoadingDrive = false;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
  }

  void _openDriveFolder() async {
    final input = _driveUrlController.text.trim();
    if (input.isEmpty) return;

    setState(() => _isLoadingDrive = true);
    final results = await _driveService.fetchFolderContents(input);
    setState(() {
      _driveItems = results;
      _isLoadingDrive = false;
    });
  }

  void _openComicReader(ComicItem comic) {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => ReaderView(comic: comic)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF121212),
      appBar: AppBar(
        backgroundColor: const Color(0xFF1F1F1F),
        title: const Row(
          children: [
            Icon(Icons.auto_stories_rounded, color: Colors.deepOrangeAccent),
            SizedBox(width: 8),
            Text('ComicVerse Reader', style: TextStyle(fontWeight: FontWeight.bold)),
          ],
        ),
        bottom: TabBar(
          controller: _tabController,
          indicatorColor: Colors.deepOrangeAccent,
          tabs: const [
            Tab(text: 'Library', icon: Icon(Icons.collections_bookmark)),
            Tab(text: 'Google Drive', icon: Icon(Icons.cloud_download)),
            Tab(text: 'Local Explorer', icon: Icon(Icons.folder)),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        children: [
          _buildLibraryTab(),
          _buildGoogleDriveTab(),
          _buildFileExplorerTab(),
        ],
      ),
    );
  }

  Widget _buildLibraryTab() {
    final recentComics = _libraryItems.where((c) => c.currentPage > 1).toList();

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (recentComics.isNotEmpty) ...[
            const Text(
              '⚡ CONTINUE READING',
              style: TextStyle(
                color: Colors.deepOrangeAccent,
                fontWeight: FontWeight.bold,
                letterSpacing: 1.2,
              ),
            ),
            const SizedBox(height: 12),
            SizedBox(
              height: 180,
              child: ListView.builder(
                scrollDirection: Axis.horizontal,
                itemCount: recentComics.length,
                itemBuilder: (context, index) {
                  final comic = recentComics[index];
                  return GestureDetector(
                    onTap: () => _openComicReader(comic),
                    child: Container(
                      width: 130,
                      margin: const EdgeInsets.only(right: 12),
                      decoration: BoxDecoration(
                        color: const Color(0xFF2C2C2C),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                            child: Container(
                              decoration: BoxDecoration(
                                color: Colors.deepOrange.shade900.withOpacity(0.3),
                                borderRadius: const BorderRadius.vertical(top: Radius.circular(12)),
                              ),
                              child: const Center(
                                child: Icon(Icons.picture_in_picture, size: 48, color: Colors.white70),
                              ),
                            ),
                          ),
                          Padding(
                            padding: const EdgeInsets.all(8.0),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  comic.title,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 12),
                                ),
                                const SizedBox(height: 4),
                                LinearProgressIndicator(
                                  value: comic.currentPage / comic.totalPages,
                                  backgroundColor: Colors.grey.shade800,
                                  color: Colors.deepOrangeAccent,
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  'Page ${comic.currentPage}/${comic.totalPages}',
                                  style: const TextStyle(color: Colors.grey, fontSize: 10),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  );
                },
              ),
            ),
            const SizedBox(height: 24),
          ],
          const Text(
            '📚 MY BOOKSHELF',
            style: TextStyle(
              color: Colors.white,
              fontWeight: FontWeight.bold,
              letterSpacing: 1.2,
            ),
          ),
          const SizedBox(height: 12),
          GridView.builder(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2,
              childAspectRatio: 0.7,
              crossAxisSpacing: 12,
              mainAxisSpacing: 12,
            ),
            itemCount: _libraryItems.length,
            itemBuilder: (context, index) {
              final comic = _libraryItems[index];
              return GestureDetector(
                onTap: () => _openComicReader(comic),
                child: Card(
                  color: const Color(0xFF242424),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                  child: Column(
                    children: [
                      Expanded(
                        child: Container(
                          decoration: BoxDecoration(
                            color: Colors.grey.shade800,
                            borderRadius: const BorderRadius.vertical(top: Radius.circular(12)),
                          ),
                          child: Stack(
                            children: [
                              const Center(child: Icon(Icons.book, size: 64, color: Colors.white30)),
                              Positioned(
                                top: 8,
                                right: 8,
                                child: Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                  decoration: BoxDecoration(
                                    color: Colors.black87,
                                    borderRadius: BorderRadius.circular(4),
                                  ),
                                  child: Text(
                                    comic.format.name.toUpperCase(),
                                    style: const TextStyle(color: Colors.amberAccent, fontSize: 10, fontWeight: FontWeight.bold),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.all(8.0),
                        child: Text(
                          comic.title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w600),
                        ),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _buildGoogleDriveTab() {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        children: [
          TextField(
            controller: _driveUrlController,
            style: const TextStyle(color: Colors.white),
            decoration: InputDecoration(
              labelText: 'Paste Google Drive Folder URL or ID',
              labelStyle: const TextStyle(color: Colors.grey),
              hintText: 'https://drive.google.com/drive/folders/...',
              hintStyle: TextStyle(color: Colors.grey.shade600),
              suffixIcon: IconButton(
                icon: const Icon(Icons.search, color: Colors.deepOrangeAccent),
                onPressed: _openDriveFolder,
              ),
              filled: true,
              fillColor: const Color(0xFF262626),
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
            ),
          ),
          const SizedBox(height: 16),
          if (_isLoadingDrive)
            const CircularProgressIndicator(color: Colors.deepOrangeAccent)
          else if (_driveItems.isEmpty)
            Expanded(
              child: Center(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.cloud_off, size: 64, color: Colors.grey.shade700),
                    const SizedBox(height: 12),
                    const Text('No Drive folder linked yet.', style: TextStyle(color: Colors.grey)),
                    const Text('Paste a link above to stream PDFs/CBZ files directly!', style: TextStyle(color: Colors.grey, fontSize: 12)),
                  ],
                ),
              ),
            )
          else
            Expanded(
              child: ListView.builder(
                itemCount: _driveItems.length,
                itemBuilder: (context, index) {
                  final comic = _driveItems[index];
                  return ListTile(
                    leading: const Icon(Icons.picture_as_pdf, color: Colors.redAccent),
                    title: Text(comic.title, style: const TextStyle(color: Colors.white)),
                    subtitle: Text(comic.folderName ?? '', style: const TextStyle(color: Colors.grey, fontSize: 11)),
                    trailing: const Icon(Icons.play_arrow_rounded, color: Colors.deepOrangeAccent),
                    onTap: () => _openComicReader(comic),
                  );
                },
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildFileExplorerTab() {
    return const Center(
      child: Text('Device File Scanner Ready\nSelect local PDF, EPUB, or CBZ files to read.', textAlign: TextAlign.center, style: TextStyle(color: Colors.grey)),
    );
  }
}
