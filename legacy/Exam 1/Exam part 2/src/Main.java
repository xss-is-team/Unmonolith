import org.apache.commons.io.FileUtils;

void main() throws IOException {

    try {

        CreateTXT("Test");

        //Search("C:\\Users\\buryk\\Documents\\*.txt");

        //Delete("C:\\Users\\buryk\\Documents\\validation2)\\*.txt");

        Copy("C:\\Users\\buryk\\Documents\\validation\\*.txt", "C:\\Users\\buryk\\Documents\\validation2");

    } catch (IOException e) {
        throw new RuntimeException(e);
    }

}

void Copy(String res, String dir)
{
    Thread searchThread = new Thread(() -> {

        try {
            int lastSlash = Math.max(res.lastIndexOf("\\"), res.lastIndexOf("/"));

            String directory = res.substring(0, lastSlash);

            String fileMask = res.substring(lastSlash + 1);

            File destinationFolder = new File(dir);

            if (!destinationFolder.exists()) {
                destinationFolder.mkdirs();
            }

            File folder = new File(directory);

            Collection<File> files = FileUtils.listFiles(
                    folder,
                    null,
                    true
            );

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fileMask);

            for (File file : files) {

                if (matcher.matches(
                        file.toPath().getFileName())) {

                    Path destination =
                            Path.of(dir, file.getName());

                    try {

                        Files.copy(file.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);

                        System.out.println("Скопирован: " + file.getAbsolutePath() + " -> " + destination);

                    } catch (IOException e) {

                        System.out.println("Ошибка копирования " + file.getAbsolutePath() + ": " + e.getMessage());
                    }
                }
            }


        } catch (Exception e) {

            System.out.println(
                    "Ошибка при копировании: " + e.getMessage()
            );
        }

    });

    searchThread.start();
}

void Delete(String Path){
    Thread searchThread = new Thread(() -> {

        try {
            int lastSlash = Math.max(Path.lastIndexOf("\\"), Path.lastIndexOf("/"));

            String directory = Path.substring(0, lastSlash);

            String fileMask = Path.substring(lastSlash + 1);

            File folder = new File(directory);

            Collection<File> files = FileUtils.listFiles(
                    folder,
                    null,
                    true
            );

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fileMask);

            for (File file : files) {

                if (matcher.matches(file.toPath().getFileName())) {

                    try {
                        Files.delete(file.toPath());

                        System.out.println("Удалён: " + file.getAbsolutePath());

                    } catch (IOException e) {

                        System.out.println("Ошибка удаления " + file.getAbsolutePath() + ": " + e.getMessage());
                    }
                }
            }


        } catch (Exception e) {

            System.out.println(
                    "Ошибка при удалении: " + e.getMessage()
            );
        }

    });

    searchThread.start();
}

void Search(String Path){

    Thread searchThread = new Thread(() -> {

        try {
            int lastSlash = Math.max(Path.lastIndexOf("\\"), Path.lastIndexOf("/"));

            String directory = Path.substring(0, lastSlash);

            String fileMask = Path.substring(lastSlash + 1);

            File folder = new File(directory);

            Collection<File> files = FileUtils.listFiles(
                    folder,
                    null,
                    true
            );

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fileMask);

            for (File file : files) {

                if (matcher.matches(file.toPath().getFileName())) {

                    String fileName = file.getName();

                    String extension = "";

                    int dotIndex = fileName.lastIndexOf(".");

                    if (dotIndex != -1) {
                        extension = fileName.substring(dotIndex + 1);
                    }


                    System.out.println("Имя: " + fileName + " | Расширение: " + extension + " | Размер: " + file.length() + " байт");
                }
            }

            //System.out.println(directory);
            //System.out.println(fileMask);

        } catch (Exception e) {

            System.out.println(
                    "Ошибка при поиске: " + e.getMessage()
            );
        }

    });

    searchThread.start();
}

void CreateTXT(String FileName) throws IOException {

    Path path = Path.of(FileName+".txt");

    if (Files.exists(path)) {
        System.out.println("Файл уже существует: " + FileName + ".txt");
        return;
    }

    Files.createFile(path);

    System.out.println("Файл создан: " + FileName + ".txt");
}
