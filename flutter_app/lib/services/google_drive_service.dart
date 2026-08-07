import 'dart:async';
import 'package:http/http' as http;
import 'dart:convert';
import '../models/comic_item.dart';

class GoogleDriveService {
  static String extractFolderId(String input) {
    if (input.contains('/folders/')) {
      final parts = input.split('/folders/');
      if (parts.length > 1) {
        return parts[1].split('?')[0].split('/')[0];
      }
    }
    return input.trim();
  }

  /// Lists PDF and comic files inside a Google Drive folder URL or ID
  Future<List<ComicItem>> fetchFolderContents(String folderUrlOrId, {String? apiKey}) async {
    final folderId = extractFolderId(folderUrlOrId);
    if (folderId.isEmpty) return [];

    final List<ComicItem> items = [];

    if (apiKey != null && apiKey.isNotEmpty) {
      final query = "'$folderId' in parents and (mimeType = 'application/pdf' or mimeType = 'application/zip' or name contains '.pdf' or name contains '.cbz')";
      final url = Uri.parse(
        'https://www.googleapis.com/drive/v3/files?q=${Uri.encodeComponent(query)}&fields=files(id,name,mimeType,thumbnailLink,webContentLink)&key=$apiKey',
      );

      try {
        final response = await http.get(url);
        if (response.statusCode == 200) {
          final data = json.decode(response.body);
          final files = data['files'] as List<dynamic>? ?? [];

          for (final f in files) {
            final name = f['name'] as String? ?? 'Untitled';
            ComicFormat fmt = ComicFormat.pdf;
            if (name.toLowerCase().endsWith('.cbz')) fmt = ComicFormat.cbz;
            if (name.toLowerCase().endsWith('.epub')) fmt = ComicFormat.epub;

            items.add(
              ComicItem(
                id: f['id'] as String,
                title: name,
                pathOrUrl: f['webContentLink'] ?? 'https://drive.google.com/uc?id=${f['id']}&export=download',
                source: ComicSource.googleDrive,
                format: fmt,
                coverUrl: f['thumbnailLink'],
                lastReadTime: DateTime.now(),
                folderName: 'Google Drive Sync',
              ),
            );
          }
        }
      } catch (e) {
        // Fallback handling
      }
    } else {
      // Mock / direct link structure if no API key provided
      items.add(
        ComicItem(
          id: 'drive_sample_1',
          title: 'Sample Drive Comic (PDF)',
          pathOrUrl: 'https://raw.githubusercontent.com/mozilla/pdf.js/master/web/compressed.tracemonkey-pldi-09.pdf',
          source: ComicSource.googleDrive,
          format: ComicFormat.pdf,
          lastReadTime: DateTime.now(),
          folderName: 'Drive Folder: $folderId',
        ),
      );
    }

    return items;
  }
}
